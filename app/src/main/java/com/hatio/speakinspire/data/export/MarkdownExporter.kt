package com.hatio.speakinspire.data.export

import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Note
import com.hatio.speakinspire.data.model.Sprout
import com.hatio.speakinspire.domain.ArticleView
import com.hatio.speakinspire.domain.Library
import com.hatio.speakinspire.domain.NoteView
import com.hatio.speakinspire.domain.TopicView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把资源导出成 Markdown。
 *
 * ## 为什么是 Markdown
 *
 * 这是唯一一个「十年后还打得开」的格式：纯文本、无私有结构、
 * 任何编辑器都认。用户把想法记在这个 App 里，最怕的是
 * 「哪天这个 App 不做了，我说过的话就没了」。
 * 能导成 md，这份担心才真正解除。
 *
 * 所以导出必须是**完整**的，不是摘要：
 * 原始记录、每一轮问答、文章全文、发芽报告、选题骨架，
 * 一个都不能少。少一样，用户就得回头来 App 里找——那这次导出就没意义。
 *
 * ## 三种资源独立之后的导出结构
 *
 * 导出仍然以记录为纲：一条记录下面挂着它的追问、文章、发芽、选题。
 * 这是**阅读顺序**，不是从属关系——数据层里删掉记录，文章依然独立存在，
 * 所以文末还会补一段「出处已删掉的文章与选题」。
 */
object MarkdownExporter {

    /** 全部内容 → 一份文档 */
    fun all(library: Library): String = buildString {
        appendLine("# 随手记 · 全部分享")
        appendLine()
        appendLine(
            "> 导出于 ${formatTime(System.currentTimeMillis())} · " +
                "${library.notes.size} 条记录 · ${library.articles.size} 篇文章 · " +
                "${library.topics.size} 个选题"
        )
        appendLine()
        appendLine("---")
        appendLine()

        library.notes.forEach { n ->
            appendLine(note(library, n).trimEnd())
            appendLine()
            appendLine("---")
            appendLine()
        }

        // 出处记录已被删掉的文章。它们是独立资产，不该因为没了出处就不导
        val orphanArticles = library.articles.filter { a ->
            library.notes.none { it.articleId == a.article.id }
        }
        orphanArticles.forEach { a ->
            appendLine(article(library, a).trimEnd())
            appendLine()
            appendLine("---")
            appendLine()
        }

        // 出处文章也没了的选题
        val knownArticleIds = library.articles.map { it.article.id }.toSet()
        val orphanTopics = library.topics.filter { it.script.sourceArticleId !in knownArticleIds }
        if (orphanTopics.isNotEmpty()) {
            appendLine("# 选题（出处文章已删除）")
            appendLine()
            orphanTopics.forEach { appendTopic(this, it) }
        }
    }

    /** 单条记录及其派生内容 */
    fun note(library: Library, noteId: String): String {
        val view = library.notes.firstOrNull { it.note.id == noteId }
            ?: return "# （这条记录不在了）\n"
        return note(library, view)
    }

    private fun note(library: Library, view: NoteView): String = buildString {
        val n = view.note
        appendLine("# ${n.displayTitle}")
        appendLine()
        appendLine("> ${formatTime(n.createdAt)} · ${n.source.label}")
        appendLine()

        if (n.body.isNotBlank()) {
            appendLine(n.body.trim())
            appendLine()
        }

        appendRounds(this, view)

        val article = library.articleOf(view)
        if (article != null) {
            appendArticleBody(this, article.article)
            appendTopics(this, library.topicsOf(article))
        }

        appendSprouts(this, view.sprouts)
    }

    /** 单篇文章及其选题。文章页的「导出」走这里 */
    fun article(library: Library, articleId: String): String {
        val view = library.articles.firstOrNull { it.article.id == articleId }
            ?: return "# （这篇文章不在了）\n"
        return article(library, view)
    }

    private fun article(library: Library, view: ArticleView): String = buildString {
        appendArticleBody(this, view.article)
        // 出处快照。文章独立之后原始记录可能已被删，
        // 这份快照是唯一还留着「这段话从哪来」的东西
        if (view.article.sourceText.isNotBlank()) {
            appendLine("## 原始记录")
            appendLine()
            appendLine("> " + view.article.sourceText.trim().replace("\n", "\n> "))
            appendLine()
        }
        appendTopics(this, library.topicsOf(view))
    }

    // ---------- 分段 ----------

    private fun appendRounds(sb: StringBuilder, view: NoteView) {
        val rounds = view.deepening?.rounds?.filter { it.answered }.orEmpty()
        if (rounds.isEmpty()) return
        sb.appendLine("## 追问")
        sb.appendLine()
        rounds.forEachIndexed { i, r ->
            sb.appendLine("**${i + 1}. ${r.question.trim()}**")
            sb.appendLine()
            sb.appendLine(r.answer?.trim().orEmpty())
            sb.appendLine()
        }
    }

    private fun appendArticleBody(sb: StringBuilder, a: Article) {
        sb.appendLine("## 文章：${a.title}")
        sb.appendLine()
        sb.appendLine("> ${formatTime(a.writtenAt)}")
        sb.appendLine()
        a.paragraphs.forEach { p ->
            sb.appendLine(p.text.trim())
            sb.appendLine()
        }
        // 正文保持干净（用户会拿它二次编辑），
        // 但「哪几段是 AI 补的」这条信息不能丢——单独列在最后一行。
        val aiParagraphs = a.paragraphs
            .withIndex()
            .filter { !it.value.mine }
            .map { it.index + 1 }
        if (aiParagraphs.isNotEmpty()) {
            sb.appendLine("> 来源标注：第 ${aiParagraphs.joinToString("、")} 段为 AI 补充的论证，其余为用户原话。")
            sb.appendLine()
        }
    }

    private fun appendSprouts(sb: StringBuilder, sprouts: List<Sprout>) {
        if (sprouts.isEmpty()) return
        // 按时间正序导出：读起来是一条「这颗种子怎么长」的线
        sprouts.sortedBy { it.createdAt }.forEach { sp ->
            sb.appendLine("## 发芽 · ${formatTime(sp.createdAt)}：${sp.title}")
            sb.appendLine()
            sp.sections.forEach { sec ->
                if (sec.seed.isNotBlank()) {
                    sb.appendLine("> 你说的：${sec.seed.trim()}")
                    sb.appendLine()
                }
                sb.appendLine(sec.insight.trim())
                sb.appendLine()
                if (sec.hasSource) {
                    // 有出处才写。没出处就空着，不在导出时替它补一个
                    sb.appendLine("—— ${sec.source.trim()}")
                    sb.appendLine()
                }
            }
        }
    }

    private fun appendTopics(sb: StringBuilder, topics: List<TopicView>) {
        val live = topics.filter { !it.script.parked }
        if (live.isEmpty()) return
        sb.appendLine("## 选题")
        sb.appendLine()
        live.forEach { appendTopic(sb, it) }
    }

    private fun appendTopic(sb: StringBuilder, t: TopicView) {
        val script = t.script
        val shot = if (script.shotAt != null) "（已拍）" else ""
        sb.appendLine("### ${script.angle}$shot")
        sb.appendLine()
        script.sections.forEach { s ->
            sb.appendLine("- **${s.title}** · ${s.seconds} 秒")
            s.phrases.forEach { p -> sb.appendLine("  - $p") }
            s.cues.forEach { c -> sb.appendLine("  - _${c.icon}：${c.text}_") }
        }
        if (script.note.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("备注：${script.note.trim()}")
        }
        sb.appendLine()
    }

    // ---------- 工具 ----------

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA)

    fun formatTime(ms: Long): String = timeFmt.format(Date(ms))

    /**
     * 文件名。
     *
     * 去掉路径分隔符和控制字符——标题是用户随手写的，
     * 里面完全可能有 `/` 或换行，直接当文件名会写失败。
     * 全角的 `：？` 一并换掉：它们本身合法，但留在文件名里
     * 会被各家文件管理器、网盘当成残缺字符处理，看着像坏了。
     */
    fun fileName(title: String, stamp: Long = System.currentTimeMillis()): String {
        val safe = title.trim()
            .replace(Regex("""[\\/:*?"<>|\n\r\t：？＊｜]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(40)
            // 末尾的点和空格要去掉：Windows 上以点结尾的文件名非法，
            // 用户把导出的 .md 拷回电脑时会失败
            .trimEnd('.', ' ')
            .ifBlank { "随手记" }
        return "$safe-${fileFmt.format(Date(stamp))}.md"
    }
}

/** 标题为空时显示什么。多处需要，收敛成一处 */
val Note.displayTitle: String get() = title.ifBlank { "（无标题）" }
