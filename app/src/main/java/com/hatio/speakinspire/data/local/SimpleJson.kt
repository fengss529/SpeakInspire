package com.hatio.speakinspire.data.local

import com.hatio.speakinspire.data.model.Cue
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.data.model.Section

/**
 * 极简 JSON 编解码。
 *
 * ## 为什么不引Moshi/Kotlinx Serialization
 *
 * 只需序列化三种结构（轮次、段落、骨架），字段固定、无嵌套多态。
 * 为此引入完整序列化库并配proguard 规则，收益不抵成本。
 *
 * ## 关键约束：必须正确转义引号
 *
 * 用户原话里出现「他说"这样"」是常态。转义没做对就会破坏整个 JSON，
 * 导致数据静默损坏——**这种bug 极难排查**，所以下面的转义必须对。
 */
internal object SimpleJson {

    fun encodeRounds(rounds: List<Round>): String =
        rounds.joinToString(",", "[", "]") { r ->
            """{"q":${quote(r.question)},"a":${r.answer?.let { quote(it) } ?: "null"}}"""
        }

    fun decodeRounds(json: String): List<Round> {
        val body = json.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        return splitObjects(body).map { obj ->
            Round(
                question = extractString(obj, "q") ?: "",
                answer = extractString(obj, "a"),
            )
        }
    }

    fun encodeParagraphs(paras: List<Paragraph>): String =
        paras.joinToString(",", "[", "]") { p ->
            """{"t":${quote(p.text)},"m":${p.mine}}"""
        }

    fun decodeParagraphs(json: String): List<Paragraph> {
        val body = json.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        return splitObjects(body).map { obj ->
            Paragraph(
                text = extractString(obj, "t") ?: "",
                mine = extractBool(obj, "m") ?: false,
            )
        }
    }

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
                title = extractString(obj, "title") ?: "",
                seconds = extractInt(obj, "sec") ?: 0,
                phrases = extractStringArray(obj, "ph"),
                cues = extractRawValue(obj, "cues")?.let { decodeCues(it) } ?: emptyList(),
            )
        }
    }

    /** cues 是对象数组，必须走 splitObjects 而非 extractString */
    private fun decodeCues(raw: String): List<Cue> {
        val body = raw.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        return splitObjects(body).map { c ->
            Cue(
                icon = extractString(c, "i") ?: "",
                text = extractString(c, "t") ?: "",
            )
        }
    }

    // ---------- 转义 ----------

    /** JSON 字符串字面量。必须转义引号、反斜杠、控制字符 */
    private fun quote(s: String): String {
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

    // ---------- 解析 ----------

    /** 按顶层逗号切分对象数组（跳过嵌套的 [] 与引号内的逗号） */
    /**
 * 按**对象之间**的逗号切分，跳过嵌套与引号内的逗号。
 *
 * ## 曾经写错的地方
 *
 * 原实现只在 `depth == 0` 时切分，但输入是 `{"a":1},{"b":2}` 这样
 * **已经去掉外层方括号**的内容——首个 `{` 让 depth 升到 1，
 * 中间的逗号永远不被识别，整串只切出 1 个元素。
 * 于是「两段 JSON 只解析出一段」，段落来源标记、多个拍摄角度全会丢。
 *
 * 现在两件事一起做：
 * 1. 先剥掉可能存在的外层方括号，让两种输入走同一条路径
 * 2. 判定条件用 `depth <= 0`，覆盖「带括号」与「不带括号」两种深度基准
 */
private fun splitObjects(raw: String): List<String> {
    var body = raw.trim()
    if (body.startsWith("[") && body.endsWith("]")) body = body.substring(1, body.length - 1)
    if (body.isBlank()) return emptyList()

    val result = mutableListOf<String>()
    var depth = 0
    var inStr = false
    var esc = false
    val sb = StringBuilder()

    for (ch in body) {
        if (esc) { sb.append(ch); esc = false; continue }
        if (ch == '\\' && inStr) { sb.append(ch); esc = true; continue }
        if (ch == '"') { inStr = !inStr; sb.append(ch); continue }
        if (!inStr) {
            when (ch) {
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth <= 0 && sb.isNotBlank()) {
                        result += sb.toString()
                        sb.clear()
                        depth = 0
                        continue
                    }
                }
                ',' -> if (depth <= 0) {
                    result += sb.toString()
                    sb.clear()
                    continue
                }
            }
        }
        sb.append(ch)
    }
    if (sb.isNotBlank()) result += sb.toString()
    return result.filter { it.isNotBlank() }
}

    private fun extractString(obj: String, key: String): String? {
        val marker = "\"$key\":"
        val idx = obj.indexOf(marker)
        if (idx < 0) return null
        var i = idx + marker.length
        if (i >= obj.length || obj[i] != '"') return null
        i++
        val sb = StringBuilder()
        var esc = false
        while (i < obj.length) {
            val ch = obj[i]
            if (esc) {
                when (ch) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'u' -> {
                        val hex = obj.substring(i + 1, i + 5)
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> sb.append(ch)
                }
                esc = false
                i++
                continue
            }
            if (ch == '\\') { esc = true; i++; continue }
            if (ch == '"') return sb.toString()
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /**
     * 解析字符串数组 `["a","b"]`。
     *
     * 不能复用 [splitObjects]——那个函数按顶层逗号切**对象**，
     * 会把引号内的逗号也当分隔符，导致
     * `["第一句, 有逗号"]` 被切成两条。
     */
    private fun extractStringArray(obj: String, key: String): List<String> {
        val raw = extractRawValue(obj, key) ?: return emptyList()
        return parseStringArray(raw)
    }

    /** 逐字符扫描，正确跳过引号内的逗号 */
    private fun parseStringArray(raw: String): List<String> {
        val result = mutableListOf<String>()
        var i = raw.indexOf('[') + 1
        val end = raw.lastIndexOf(']').let { if (it < 0) raw.length else it }
        val sb = StringBuilder()
        var inStr = false
        var esc = false
        var started = false

        while (i < end) {
            val ch = raw[i]
            if (inStr) {
                if (esc) {
                    when (ch) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'u' -> {
                            val hex = raw.substring(i + 1, i + 5)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            i += 4
                        }
                        else -> sb.append(ch)
                    }
                    esc = false
                } else if (ch == '\\') {
                    esc = true
                } else if (ch == '"') {
                    result += sb.toString()
                    sb.clear()
                    inStr = false
                    started = false
                } else {
                    sb.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> { inStr = true; started = true }
                    // 数组元素间的空白与逗号都跳过
                }
            }
            i++
        }
        // 容错：数组没闭合时，把残留内容也算进去
        if (started && sb.isNotEmpty()) result += sb.toString()
        return result
    }

    /**
     * 取某个 key 的原始值（仍带引号/括号）。
     *
     * 按值类型分三种走法——这是修掉过两个 bug 后的稳定版本：
     *
     * 1. **容器**（`[` 或 `{` 开头）→ 跟到配对的结束符。
     *    曾经写成「depth == 0 就返回」，结果值本身是数组时 depth 会升到 1，
     *    遇到内层 `]` 不触发返回，一路把后面的同层字段也吞进来。
     * 2. **字符串**（`"` 开头）→ 跟到闭引号。要保留首尾引号，
     *    下游 `extractString` 还靠它定位。
     * 3. **标量**（数字/布尔/null）→ 到逗号或 `}` 为止。
     */
    private fun extractRawValue(obj: String, key: String): String? {
        val marker = "\"$key\":"
        val idx = obj.indexOf(marker)
        if (idx < 0) return null
        var i = idx + marker.length
        if (i >= obj.length) return null

        val first = obj[i]
        var depth = 0
        var inStr = false
        var esc = false
        val sb = StringBuilder()

        when {
            // 1. 容器
            first == '[' || first == '{' -> {
                while (i < obj.length) {
                    val ch = obj[i]
                    if (esc) { sb.append(ch); esc = false; i++; continue }
                    if (ch == '\\' && inStr) { sb.append(ch); esc = true; i++; continue }
                    if (ch == '"') { inStr = !inStr; sb.append(ch); i++; continue }
                    if (!inStr) {
                        if (ch == '[' || ch == '{') depth++
                        else if (ch == ']' || ch == '}') {
                            depth--
                            if (depth == 0) { sb.append(ch); return sb.toString() }
                        }
                    }
                    sb.append(ch)
                    i++
                }
            }

            // 2. 字符串
            first == '"' -> {
                sb.append('"')
                i++
                esc = false
                while (i < obj.length) {
                    val ch = obj[i]
                    if (esc) { sb.append(ch); esc = false; i++; continue }
                    if (ch == '\\') { sb.append(ch); esc = true; i++; continue }
                    if (ch == '"') { sb.append('"'); return sb.toString() }
                    sb.append(ch)
                    i++
                }
            }

            // 3. 标量
            else -> {
                while (i < obj.length && obj[i] != ',' && obj[i] != '}') {
                    sb.append(obj[i])
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun extractBool(obj: String, key: String): Boolean? =
        extractRawValue(obj, key)?.trim()?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    private fun extractInt(obj: String, key: String): Int? =
        extractRawValue(obj, key)?.trim()?.toIntOrNull()
}