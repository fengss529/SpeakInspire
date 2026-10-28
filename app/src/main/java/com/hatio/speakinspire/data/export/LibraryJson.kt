package com.hatio.speakinspire.data.export

import com.hatio.speakinspire.data.local.SimpleJson
import com.hatio.speakinspire.data.local.extractArrayField
import com.hatio.speakinspire.data.local.extractField
import com.hatio.speakinspire.data.local.extractRawValue
import com.hatio.speakinspire.data.local.splitObjects
import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.Note
import com.hatio.speakinspire.data.model.NoteSource
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.data.model.Sprout
import com.hatio.speakinspire.domain.Library

/**
 * 全量数据 → `data.json`。
 *
 * ## 为什么除了 md 还要一份 json
 *
 * md 是给人读的：用户换台设备、换个 App、十年后打开文本编辑器，
 * 都能看懂自己写过什么。但 md 丢了「这是第几轮回答」「这段是 AI 补的还是我说的」
 * 这类结构——它是**阅读格式**，不是**迁移格式**。
 *
 * 所以导出同时给一份 json：字段一一对应库里的表，
 * 将来换手机、换实现，靠它把内容原样搬回去。
 * md 保证「永远能读懂」，json 保证「永远能带走」。
 *
 * ## schemaVersion
 *
 * 这是**数据结构的版本**，跟数据库版本同步（现在都是 4）。
 * 导入时要先看它：版本比当前高就拒绝，而不是尝试兼容——
 * 用旧代码读新结构，最坏的结果是**静默丢掉用户没见过的字段**，
 * 那比明确报错糟得多。
 *
 * ## 转义规则与库里一致
 *
 * [SimpleJson] 的编解码直接复用。用户原话里出现引号是常态，
 * 导出路径要是自己写一套转义，迟早两边对不上，
 * 表现为「导出的文件里少了几句」——这种损坏不可逆。
 */
object LibraryJson {

    /** 与 [com.hatio.speakinspire.data.local.SpeakInspireDatabase] 的 version 同步 */
    const val SCHEMA_VERSION = 4

    const val FILE_NAME = "data.json"

    /**
     * 从 json 还原出来的内容。
     *
     * 刻意用领域模型而不是 Entity：导入时要不要改 id、要不要合并，
     * 是导入那一版的事。这里只负责「文件说了什么」。
     */
    data class Snapshot(
        val schemaVersion: Int,
        val notes: List<Note>,
        val deepenings: List<Deepening>,
        val sprouts: List<Sprout>,
        val articles: List<Article>,
        val topics: List<Script>,
    ) {
        val isEmpty: Boolean
            get() = notes.isEmpty() && articles.isEmpty() && topics.isEmpty()
    }

    // ---------- 编码 ----------

    fun encode(
        library: Library,
        appVersion: String,
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val notes = library.notes.map { view ->
            Json.obj(
                "id" to Json.text(view.note.id),
                "title" to Json.text(view.note.title),
                "body" to Json.text(view.note.body),
                "createdAt" to Json.number(view.note.createdAt),
                "source" to Json.text(view.note.source.name),
                "parentId" to Json.textOrNull(view.note.parentId),
                // 追问和发芽跟着记录走：数据层里它们是 note 的子表，
                // 记录没了它们也留不住，所以放在同一条下面读起来才对应
                "deepening" to (view.deepening?.let { d ->
                    Json.obj(
                        "id" to Json.text(d.id),
                        "startedAt" to Json.number(d.startedAt),
                        "finishedAt" to Json.numberOrNull(d.finishedAt),
                        "estimatedRounds" to Json.number(d.estimatedRounds.toLong()),
                        "rounds" to Json.raw(SimpleJson.encodeRounds(d.rounds)),
                    )
                }?.let { Json.raw(it) } ?: "null"),
                "sprouts" to Json.raw(
                    view.sprouts.sortedBy { it.createdAt }.joinToString(",", "[", "]") { sp ->
                        Json.obj(
                            "id" to Json.text(sp.id),
                            "title" to Json.text(sp.title),
                            "createdAt" to Json.number(sp.createdAt),
                            "sections" to Json.raw(SimpleJson.encodeSproutSections(sp.sections)),
                        )
                    }
                ),
                // 只是「这条记录成过文」的线索。文章正文在下面独立的数组里，
                // 因为文章是独立资源，不该被塞进记录的字段里读
                "articleId" to Json.textOrNull(view.articleId),
            )
        }

        val articles = library.articles.map { a ->
            Json.obj(
                "id" to Json.text(a.article.id),
                "sourceNoteId" to Json.textOrNull(a.article.sourceNoteId),
                "sourceText" to Json.text(a.article.sourceText),
                "title" to Json.text(a.article.title),
                "writtenAt" to Json.number(a.article.writtenAt),
                "edited" to Json.bool(a.article.edited),
                "paragraphs" to Json.raw(SimpleJson.encodeParagraphs(a.article.paragraphs)),
            )
        }

        val topics = library.topics.map { t ->
            Json.obj(
                "id" to Json.text(t.script.id),
                "sourceArticleId" to Json.textOrNull(t.script.sourceArticleId),
                "sourceTitle" to Json.text(t.script.sourceTitle),
                "angle" to Json.text(t.script.angle),
                "sections" to Json.raw(SimpleJson.encodeSections(t.script.sections)),
                "createdAt" to Json.number(t.script.createdAt),
                "shotAt" to Json.numberOrNull(t.script.shotAt),
                "parked" to Json.bool(t.script.parked),
                "note" to Json.text(t.script.note),
            )
        }

        return Json.pretty(
            Json.obj(
            "schemaVersion" to Json.number(SCHEMA_VERSION.toLong()),
            "app" to Json.text("随手记"),
            "appVersion" to Json.text(appVersion),
            "exportedAt" to Json.number(exportedAt),
            // 字段名不能叫 notes / articles / topics ——
            // 那是下面三个数组的 key，重名会让解析器取到前面这个数字
            "counts" to Json.obj(
                "note" to Json.number(library.notes.size.toLong()),
                "article" to Json.number(library.articles.size.toLong()),
                "topic" to Json.number(library.topics.size.toLong()),
            ),
            "notes" to Json.raw(notes.joinToString(",\n    ", "[\n    ", "\n  ]")),
            "articles" to Json.raw(articles.joinToString(",\n    ", "[\n    ", "\n  ]")),
            "topics" to Json.raw(topics.joinToString(",\n    ", "[\n    ", "\n  ]")),
            )
        )
    }

    // ---------- 解码 ----------

    /**
     * 从 `data.json` 读回内容。
     *
     * 返回 null 表示这根本不是我们的文件（没有 schemaVersion）。
     * 结构坏掉的单条**跳过、不整体失败**：
     * 一条记录解析不出来，不该让另外 200 条也导不进去。
     */
    fun decode(json: String): Snapshot? {
        val version = extractField(json, "schemaVersion")?.trim()?.toIntOrNull() ?: return null

        val noteObjs = splitObjects(extractArrayField(json, "notes").orEmpty())
        val articles = splitObjects(extractArrayField(json, "articles").orEmpty()).mapNotNull { decodeArticle(it) }
        val topics = splitObjects(extractArrayField(json, "topics").orEmpty()).mapNotNull { decodeTopic(it) }

        // 追问与发芽嵌在记录里，这里摊平出来。
        // 导入侧要往 note 子表插数据，摊平了才好写
        val notes = mutableListOf<Note>()
        val deepenings = mutableListOf<Deepening>()
        val sprouts = mutableListOf<Sprout>()
        noteObjs.forEach { obj ->
            decodeNote(obj)?.let(notes::add)
            decodeDeepening(obj)?.let(deepenings::add)
            splitObjects(extractArrayField(obj, "sprouts").orEmpty())
                .mapNotNull { decodeSprout(obj, it) }
                .forEach(sprouts::add)
        }

        return Snapshot(
            schemaVersion = version,
            notes = notes,
            deepenings = deepenings,
            sprouts = sprouts,
            articles = articles,
            topics = topics,
        )
    }

    private fun decodeNote(obj: String): Note? {
        val id = extractField(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
        return Note(
            id = id,
            title = extractField(obj, "title").orEmpty(),
            body = extractField(obj, "body").orEmpty(),
            createdAt = extractField(obj, "createdAt")?.toLongOrNull() ?: 0L,
            // 枚举名读不出来就退回 QUICK，而不是整条丢掉——
            // 来源只是个标签，缺了不影响记录本身的价值
            source = extractField(obj, "source")
                ?.let { runCatching { NoteSource.valueOf(it) }.getOrNull() }
                ?: NoteSource.QUICK,
            parentId = extractField(obj, "parentId")?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun decodeDeepening(noteObj: String): Deepening? {
        val raw = extractRawValue(noteObj, "\"deepening\":") ?: return null
        if (raw == "null" || !raw.startsWith("{")) return null
        val id = extractField(raw, "id")?.takeIf { it.isNotBlank() } ?: return null
        val noteId = extractField(noteObj, "id") ?: return null
        return Deepening(
            id = id,
            noteId = noteId,
            rounds = extractArrayField(raw, "rounds")?.let(SimpleJson::decodeRounds).orEmpty(),
            startedAt = extractField(raw, "startedAt")?.toLongOrNull() ?: 0L,
            finishedAt = extractField(raw, "finishedAt")?.toLongOrNull(),
            estimatedRounds = extractField(raw, "estimatedRounds")?.toIntOrNull() ?: 5,
        )
    }

    private fun decodeSprout(noteObj: String, obj: String): Sprout? {
        val id = extractField(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
        val noteId = extractField(noteObj, "id") ?: return null
        return Sprout(
            id = id,
            noteId = noteId,
            title = extractField(obj, "title").orEmpty(),
            sections = extractArrayField(obj, "sections")
                ?.let(SimpleJson::decodeSproutSections).orEmpty(),
            createdAt = extractField(obj, "createdAt")?.toLongOrNull() ?: 0L,
        )
    }

    private fun decodeArticle(obj: String): Article? {
        val id = extractField(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
        return Article(
            id = id,
            sourceNoteId = extractField(obj, "sourceNoteId")?.takeIf { it.isNotBlank() && it != "null" },
            sourceText = extractField(obj, "sourceText").orEmpty(),
            title = extractField(obj, "title").orEmpty(),
            paragraphs = extractArrayField(obj, "paragraphs")
                ?.let(SimpleJson::decodeParagraphs).orEmpty(),
            writtenAt = extractField(obj, "writtenAt")?.toLongOrNull() ?: 0L,
            edited = extractField(obj, "edited")?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun decodeTopic(obj: String): Script? {
        val id = extractField(obj, "id")?.takeIf { it.isNotBlank() } ?: return null
        return Script(
            id = id,
            sourceArticleId = extractField(obj, "sourceArticleId")
                ?.takeIf { it.isNotBlank() && it != "null" },
            sourceTitle = extractField(obj, "sourceTitle").orEmpty(),
            angle = extractField(obj, "angle").orEmpty(),
            sections = extractArrayField(obj, "sections")
                ?.let(SimpleJson::decodeSections).orEmpty(),
            createdAt = extractField(obj, "createdAt")?.toLongOrNull() ?: 0L,
            shotAt = extractField(obj, "shotAt")?.toLongOrNull(),
            parked = extractField(obj, "parked")?.toBooleanStrictOrNull() ?: false,
            note = extractField(obj, "note").orEmpty(),
        )
    }

    // ---------- 拼装 ----------

    /**
     * 极简 JSON 拼装器。
     *
     * ## 为什么值得单独写一个
     *
     * 上一个版本里，请求体是手工拼字符串的，**少写了一个逗号**，
     * 产出 `[{system}{user}]` 这种非法 JSON，服务商稳定 400。
     * 那个 bug 活了整个开发周期，因为测试全都在断言 `body.contains(...)`——
     * 对语法错误完全免疫。
     *
     * 这里的每个字段都走 `Pair` + `joinToString(",")`，逗号由结构决定，
     * 不再由人手决定。少一个字段最多是少一个字段，不会变成非法文件。
     */
    private object Json {

        fun text(v: String): String = SimpleJson.quote(v)

        fun textOrNull(v: String?): String = v?.let(SimpleJson::quote) ?: "null"

        fun number(v: Long): String = v.toString()

        fun numberOrNull(v: Long?): String = v?.toString() ?: "null"

        fun bool(v: Boolean): String = v.toString()

        /** 已经是 JSON 片段的（嵌套对象、数组）原样放进去 */
        fun raw(v: String): String = v

        fun obj(vararg fields: Pair<String, String>): String =
            fields.joinToString(",", "{", "}") { (k, v) -> "${SimpleJson.quote(k)}:$v" }

        /** 顶层包一层，顺便按行断开，用户点开看一眼不像坏文件 */
        fun pretty(body: String): String {
            val loose = body.removePrefix("{").removeSuffix("}")
            val parts = splitTopLevel(loose)
            val out = StringBuilder("{\n")
            parts.forEachIndexed { i, part ->
                out.append("  ").append(part.trim())
                if (i < parts.lastIndex) out.append(',')
                out.append('\n')
            }
            return out.append('}').append('\n').toString()
        }

        /** 按顶层逗号切——引号里的逗号不算，数组/对象里的逗号也不算 */
        private fun splitTopLevel(body: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var inStr = false
            var esc = false
            val sb = StringBuilder()
            for (ch in body) {
                when {
                    esc -> { sb.append(ch); esc = false }
                    inStr && ch == '\\' -> { sb.append(ch); esc = true }
                    ch == '"' -> { inStr = !inStr; sb.append(ch) }
                    inStr -> sb.append(ch)
                    ch == '[' || ch == '{' -> { depth++; sb.append(ch) }
                    ch == ']' || ch == '}' -> { depth--; sb.append(ch) }
                    ch == ',' && depth == 0 -> { parts += sb.toString(); sb.clear() }
                    else -> sb.append(ch)
                }
            }
            if (sb.isNotBlank()) parts += sb.toString()
            return parts.filter { it.isNotBlank() }
        }
    }
}
