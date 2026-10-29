package com.hatio.speakinspire.data.local

import com.hatio.speakinspire.data.model.Cue
import com.hatio.speakinspire.data.model.Material
import com.hatio.speakinspire.data.model.MaterialKind
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.data.model.Section
import com.hatio.speakinspire.data.model.SproutSection

/**
 * 本地存储用的极简 JSON 编解码。
 *
 * ## 为什么不引 Moshi / Kotlinx Serialization
 *
 * 只需序列化五种结构（轮次、段落、骨架段、发芽节、镜头提示），
 * 字段固定、无嵌套多态。为此引入完整序列化库并配 ProGuard 规则，
 * 收益不抵成本。而且这些数据是**要留十年的用户资产**，
 * 格式越简单、依赖越少，将来迁移越容易。
 *
 * ## 解析逻辑不在这里
 *
 * 扫描 JSON 的那些函数（`splitObjects`、`extractRawValue`、
 * `parseStringArray`）与 `RealAiService` 解析模型输出用的是同一套，
 * 已经收进 [JsonScan]。这里只负责「结构 ↔ 字符串」的映射。
 *
 * ## 关键约束：必须正确转义引号
 *
 * 用户原话里出现「他说"这样"」是常态。转义没做对就会破坏整个 JSON，
 * 导致数据静默损坏——**这种 bug 极难排查**，所以下面的转义必须对。
 */
internal object SimpleJson {

    // ---------- 追问轮次 ----------

    fun encodeRounds(rounds: List<Round>): String =
        rounds.joinToString(",", "[", "]") { r ->
            """{"q":${quote(r.question)},"a":${r.answer?.let { quote(it) } ?: "null"}}"""
        }

    fun decodeRounds(json: String): List<Round> {
        val body = json.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        return splitObjects(body).map { obj ->
            Round(
                question = extractField(obj, "q") ?: "",
                answer = extractField(obj, "a"),
            )
        }
    }

    // ---------- 文章段落 ----------

    /**
     * 段落。
     *
     * 第三个字段 `s`（fromSprout）是 v5 加的。**解码必须容忍它不存在**——
     * v5 之前的文章只有 `t` 和 `m`，读的时候按 false 补上即可。
     * 少一个字段就把整段判成坏数据，会让老文章一片空白。
     */
    fun encodeParagraphs(paras: List<Paragraph>): String =
        paras.joinToString(",", "[", "]") { p ->
            """{"t":${quote(p.text)},"m":${p.mine},"s":${p.fromSprout}}"""
        }

    fun decodeParagraphs(json: String): List<Paragraph> {
        val body = json.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        return splitObjects(body).map { obj ->
            Paragraph(
                text = extractField(obj, "t") ?: "",
                mine = extractBool(obj, "m") ?: false,
                fromSprout = extractBool(obj, "s") ?: false,
            )
            // 别人说的话永远不能标成你说的。存储层再兜一次，
            // 免得某天有人从别的路径写进一个自相矛盾的组合
        }.map { if (it.mine && it.fromSprout) it.copy(fromSprout = false) else it }
    }

    // ---------- 选题素材 ----------

    fun encodeMaterials(list: List<Material>): String =
        list.joinToString(",", "[", "]") { m ->
            """{"k":${quote(m.kind.name)},"t":${quote(m.text)},"src":${quote(m.source)}}"""
        }

    fun decodeMaterials(json: String): List<Material> {
        val body = json.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        return splitObjects(body).mapNotNull { obj ->
            val text = extractField(obj, "t") ?: return@mapNotNull null
            Material(
                // 存的是枚举名；认不出来当例子，别整条扔
                kind = runCatching {
                    MaterialKind.valueOf(extractField(obj, "k").orEmpty())
                }.getOrDefault(MaterialKind.EXAMPLE),
                text = text,
                source = extractField(obj, "src").orEmpty(),
            )
        }
    }

    // ---------- 拍摄骨架 ----------

    fun encodeSections(sections: List<Section>): String =
        sections.joinToString(",", "[", "]") { s ->
            val cues = s.cues.joinToString(",", "[", "]") { c ->
                """{"i":${quote(c.icon)},"t":${quote(c.text)}}"""
            }
            val phrases = s.phrases.joinToString(",", "[", "]") { quote(it) }
            """{"title":${quote(s.title)},"sec":${s.seconds},"ph":$phrases,"cues":$cues}"""
        }

    fun decodeSections(json: String): List<Section> {
        val body = json.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        return splitObjects(body).map { obj ->
            Section(
                title = extractField(obj, "title") ?: "",
                seconds = extractInt(obj, "sec") ?: 0,
                phrases = extractStringArray(obj, "ph"),
                cues = extractRawValue(obj, "\"cues\":")?.let { decodeCues(it) } ?: emptyList(),
            )
        }
    }

    /** cues 是对象数组，必须走 splitObjects 而非 extractField */
    private fun decodeCues(raw: String): List<Cue> {
        val body = raw.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        return splitObjects(body).map { c ->
            Cue(
                icon = extractField(c, "i") ?: "",
                text = extractField(c, "t") ?: "",
            )
        }
    }

    // ---------- 发芽报告 ----------

    /**
     * 字段名压到最短（seed / ins / src）：发芽报告动辄几百字，
     * 每条记录存若干份，长字段名的存储与解析开销都不划算。
     * 但**解码时必须按短名读**，改名就等于旧报告全部解析成空。
     */
    fun encodeSproutSections(sections: List<SproutSection>): String =
        sections.joinToString(",", "[", "]") { s ->
            """{"seed":${quote(s.seed)},"ins":${quote(s.insight)},"src":${quote(s.source)}}"""
        }

    fun decodeSproutSections(json: String): List<SproutSection> {
        val body = json.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        return splitObjects(body).map { obj ->
            SproutSection(
                seed = extractField(obj, "seed") ?: "",
                insight = extractField(obj, "ins") ?: "",
                source = extractField(obj, "src") ?: "",
            )
        }.filter { it.insight.isNotBlank() }
    }

    // ---------- 转义 ----------

    /**
     * JSON 字符串字面量。必须转义引号、反斜杠、控制字符。
     *
     * 对外可见是为了 [com.hatio.speakinspire.data.export.LibraryJson]——
     * 导出的 data.json 与库里存的是同一套转义规则。
     * 两处各写一份的话，「他说"这样"」这种原话迟早会在其中一条路上坏掉。
     */
    internal fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else ->
                    if (ch < ' ') {
                        sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ---------- 类型转换 ----------

    private fun extractBool(obj: String, key: String): Boolean? =
        extractField(obj, key)?.trim()?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    private fun extractInt(obj: String, key: String): Int? =
        extractField(obj, key)?.trim()?.toIntOrNull()
}
