package com.hatio.speakinspire.data.local

/**
 * 手写 JSON 扫描工具。
 *
 * ## 为什么会有这个文件
 *
 * 项目里有**两处**需要从模型输出或本地存储的 JSON 里取值：
 * 一是 `SimpleJson`（读本地的 rounds / paragraphs / sections），
 * 二是 `RealAiService` 解析模型的回复。
 *
 * 之前这两处各自写了一套几乎相同的扫描器（`splitObjects`、
 * `extractRawValue`、`parseStringArray`），修 bug 时得改两遍，
 * 而且已经出现了细微差异——其中一份处理「数组没闭合」的容错，
 * 另一份没有。这属于典型的**同一份知识存在两个副本**。
 *
 * 现在只有这一份。`SimpleJson` 与 `RealAiService` 都调它。
 *
 * ## 实现要点：按值类型分三种走法
 *
 * 容器跟到配对结束符、字符串跟到闭引号、标量到逗号。
 * 曾踩过的坑是「只看 depth==0 就返回」——值本身是数组时
 * depth 会升到 1，遇到内层 `]` 不触发返回，会把后续字段一起吞进来。
 */

/** 取某个 key 的字符串值（已去引号、解转义） */
internal fun extractField(obj: String, key: String): String? =
    extractRawValue(obj, "\"$key\":")?.let { unquoteIfNeeded(it) }

/** 取某个 key 的数组值（仍带方括号） */
internal fun extractArrayField(obj: String, key: String): String? =
    extractRawValue(obj, "\"$key\":")?.takeIf { it.startsWith("[") }

/** 取第一个字符串字面量。模型偶尔返回不带 key 的裸字符串 */
internal fun extractFirstString(obj: String): String? {
    val i = obj.indexOf('"')
    if (i < 0) return null
    val j = obj.indexOf('"', i + 1)
    if (j < 0) return null
    return obj.substring(i + 1, j)
}

internal fun extractStringArray(obj: String, key: String): List<String> {
    val raw = extractRawValue(obj, "\"$key\":") ?: return emptyList()
    return parseStringArray(raw)
}

/**
 * 解析字符串数组 `["a","b"]`。
 *
 * 不能复用 [splitObjects]——那个按顶层逗号切**对象**，
 * 会把引号内的逗号也当分隔符，`["第一句, 有逗号"]` 会被切成两条。
 */
private fun parseStringArray(raw: String): List<String> {
    val result = mutableListOf<String>()
    val start = raw.indexOf('[') + 1
    if (start <= 0) return result
    val end = raw.lastIndexOf(']').let { if (it < 0) raw.length else it }

    var i = start
    val sb = StringBuilder()
    var inStr = false
    var esc = false
    var started = false

    while (i < end) {
        val ch = raw[i]
        if (inStr) {
            when {
                esc -> {
                    when (ch) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'u' -> {
                            val hex = raw.substring(
                                (i + 1).coerceAtMost(raw.length),
                                (i + 5).coerceAtMost(raw.length),
                            )
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            i += 4
                        }
                        else -> sb.append(ch)
                    }
                    esc = false
                }
                ch == '\\' -> esc = true
                ch == '"' -> {
                    result += sb.toString()
                    sb.clear()
                    inStr = false
                    started = false
                }
                else -> sb.append(ch)
            }
        } else if (ch == '"') {
            inStr = true
            started = true
        }
        i++
    }
    // 容错：数组没闭合时，把残留内容也算进去
    if (started && sb.isNotEmpty()) result += sb.toString()
    return result
}

/**
 * 按**对象之间**的逗号切分，跳过嵌套与引号内的逗号。
 *
 * ## 曾经写错的地方
 *
 * 原实现只在 `depth == 0` 时切分，但输入常是 `{"a":1},{"b":2}` 这种
 * **已经去掉外层方括号**的内容——首个 `{` 让 depth 升到 1，
 * 中间的逗号永远不被识别，整串只切出 1 个元素。
 * 于是「两段 JSON 只解析出一段」，段落来源标记、多个拍摄角度全会丢。
 *
 * 现在两件事一起做：
 * 1. 先剥掉可能存在的外层方括号，让两种输入走同一条路径
 * 2. 判定条件用 `depth <= 0`，覆盖「带括号」与「不带括号」两种深度基准
 */
internal fun splitObjects(raw: String): List<String> {
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

/**
 * 取 key 的原始值（可能带引号/括号）。
 *
 * 三种走法：容器跟到配对结束符、字符串跟到闭引号、标量到逗号。
 */
internal fun extractRawValue(obj: String, marker: String): String? {
    val idx = obj.indexOf(marker)
    if (idx < 0) return null
    var i = idx + marker.length
    // 跳过冒号与值之间的空白——模型输出常带空格
    while (i < obj.length && obj[i].isWhitespace()) i++
    if (i >= obj.length) return null

    val first = obj[i]
    var depth = 0
    var inStr = false
    var esc = false
    val sb = StringBuilder()

    when {
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

        else -> {
            while (i < obj.length && obj[i] != ',' && obj[i] != '}') {
                sb.append(obj[i])
                i++
            }
        }
    }
    return sb.toString()
}

/** 去掉首尾引号并解转义 */
internal fun unquoteIfNeeded(raw: String): String? {
    val t = raw.trim()
    if (t.length < 2 || !t.startsWith("\"") || !t.endsWith("\"")) {
        // 标量（数字/布尔）直接返回
        return t.takeIf { it.isNotEmpty() && it != "null" }
    }
    val body = t.substring(1, t.length - 1)
    val sb = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val ch = body[i]
        if (ch == '\\' && i + 1 < body.length) {
            when (val esc = body[i + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'b' -> sb.append('\b')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'u' -> {
                    val hex = body.substring(
                        (i + 2).coerceAtMost(body.length),
                        (i + 6).coerceAtMost(body.length),
                    )
                    sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                    i += 4
                }
                else -> sb.append(esc)
            }
            i += 2
        } else {
            sb.append(ch)
            i++
        }
    }
    return sb.toString()
}
