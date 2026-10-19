package com.hatio.speakinspire.data.remote

import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.data.model.Section
import com.hatio.speakinspire.data.model.Cue

/**
 * 请求体构造。
 *
 * ## 家服务商的 thinking 控制
 *
 * 两家都必须显式发 `thinking:{"type":"disabled"}`（实测 2026-10-16）：
 * 不发的话会返回 `reasoning_content` 而 `content` 为空，
 * 追问的逐字浮现会变成漫长的空白等待。
 *
 * ## ⚠️ 四个 builder 共用一条拼接路径
 *
 * 曾经 `probe`/`compose`/`angles`/`outline` 各拼各的 JSON，每个都写了一遍
 * `messages` 数组的循环。其中两个漏了 system 后面的逗号，
 * 产出 `[{system}{user}]` 这种非法 JSON——服务商稳定返回 400，
 * 界面只显示「出了点问题，具体原因看不出来」。
 *
 * 表现就是：**追问提不出问题、写成文章也走不通**，而所有测试都是绿的
 * （测试用的是 `body.contains(...)`，对语法错误完全免疫）。
 *
 * 现在只有 [chatBody] 一处拼 messages，四个入口都走它。
 * `ProbeRequestJsonTest` 会把每个 builder 的产出真解析一遍。
 */
object RequestBuilder {

    const val CHAT_PATH = "/chat/completions"

    /** 追问：流式，且要模型只提问不给答案 */
    fun probe(
        cfg: ProviderConfig,
        systemPrompt: String,
        messages: List<ChatMessage>,
        model: String? = null,
    ): String = chatBody(
        cfg = cfg,
        systemPrompt = systemPrompt,
        messages = messages,
        model = model,
        stream = true,
        temperature = 0.7,
        maxTokens = 200,
    )

    /** 成文：一次性，要求返回 JSON，带 isMine 标注 */
    fun compose(
        cfg: ProviderConfig,
        systemPrompt: String,
        messages: List<ChatMessage>,
        model: String? = null,
    ): String = chatBody(
        cfg = cfg,
        systemPrompt = systemPrompt,
        messages = messages,
        model = model,
        stream = false,
        temperature = 0.6,
        jsonMode = true,
    )

    /** 拆角度：一次性，返回 JSON 数组 */
    fun angles(
        cfg: ProviderConfig,
        systemPrompt: String,
        articleText: String,
        model: String? = null,
    ): String = chatBody(
        cfg = cfg,
        systemPrompt = systemPrompt,
        messages = listOf(ChatMessage.user(articleText)),
        model = model,
        stream = false,
        temperature = 0.8,
        jsonMode = true,
    )

    /** 生成骨架：一次性 */
    fun outline(
        cfg: ProviderConfig,
        systemPrompt: String,
        angle: String,
        model: String? = null,
    ): String = chatBody(
        cfg = cfg,
        systemPrompt = systemPrompt,
        messages = listOf(ChatMessage.user("切入角度：$angle")),
        model = model,
        stream = false,
        temperature = 0.7,
        jsonMode = true,
    )

    /** 连接测试：最短请求，不消耗对话配额 */
    fun ping(cfg: ProviderConfig): String =
        "{\"model\":" + jsonString(cfg.model) + "," +
            "\"max_tokens\":1," +
            "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"

    /**
     * 唯一的请求体拼接入口。
     *
     * 顺序与逗号都在这里一次写对，调用方不可能再漏。
     */
    private fun chatBody(
        cfg: ProviderConfig,
        systemPrompt: String,
        messages: List<ChatMessage>,
        model: String?,
        stream: Boolean,
        temperature: Double,
        maxTokens: Int? = null,
        jsonMode: Boolean = false,
    ): String = buildString {
        append("{")
        append("\"model\":").append(jsonString(model ?: cfg.model)).append(",")
        append(thinkingPrefix(cfg))
        append("\"stream\":").append(stream).append(",")
        append("\"temperature\":").append(temperature).append(",")
        if (maxTokens != null) append("\"max_tokens\":").append(maxTokens).append(",")
        if (jsonMode) append("\"response_format\":{\"type\":\"json_object\"},")

        append("\"messages\":[")
        // system 永远排第一。这里就把 messages 数组完整拼出来，
        // 逗号只在「不是第一条」时加——一处对，处处对。
        append("{\"role\":\"system\",\"content\":").append(jsonString(systemPrompt)).append("}")
        messages.forEach { m ->
            append(",")
            append("{\"role\":").append(jsonString(if (m.isUser) "user" else "assistant"))
            append(",\"content\":").append(jsonString(m.content)).append("}")
        }
        append("]")
        append("}")
    }

    /**
     * thinking 参数前缀。
     *
     * 两家默认都开思考，必须显式关闭；不需要时**一个字节都不发**。
     */
    private fun thinkingPrefix(cfg: ProviderConfig): String =
        if (ProviderDialect.needsThinkingParam(cfg) && cfg.thinkingDisabled)
            "\"thinking\":{\"type\":\"disabled\"},"
        else ""

    /** JSON 字符串转义 —— 用户原话里出现引号是常态 */
    fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
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
}

/**
 * 从完整响应里抽出 `choices[0].message.content`。
 *
 * 手写而非引 JSON 库——响应结构固定，且要避开 ProGuard 规则。
 */
object ResponseParser {

    /** 抽出非流式响应里的 content */
    fun extractContent(body: String): String? {
        // 优先 message.content，其次 choices[0].text（兼容部分服务商）
        val keys = listOf("\"content\":", "\"text\":")
        for (key in keys) {
            val v = extractRawValue(body, key)
            if (v != null && v.startsWith("\"")) return unquote(v)
        }
        return null
    }

    /** 完整端点：baseUrl 可能带/ 不带尾部斜杠，也可能有自定义路径 */
    fun endpoint(baseUrl: String, path: String = RequestBuilder.CHAT_PATH): String {
        val trimmed = baseUrl.trimEnd('/')
        // 用户在自定义里填了完整端点就直接用
        if (trimmed.endsWith("/chat/completions")) return trimmed
        return trimmed + path
    }

    private fun unquote(raw: String): String? {
        if (raw.length < 2 || !raw.startsWith("\"") || !raw.endsWith("\"")) return null
        val body = raw.substring(1, raw.length - 1)
        val sb = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch == '\\' && i + 1 < body.length) {
                when (val esc = body[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = body.substring(i + 2, i + 6)
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

    /**
     * 取 key 的原始值。
     *
     * 照搬 SimpleJson 里验证过的那版实现——**按值类型分三种走法**：
     * 容器跟到配对结束符、字符串跟到闭引号、标量到逗号。
     * 之前踩过的坑：只看 depth==0 会在值本身是数组时吞掉后续字段。
     */
    private fun extractRawValue(obj: String, key: String): String? {
        val marker = key
        val idx = obj.indexOf(marker)
        if (idx < 0) return null
        var i = idx + marker.length
        // 跳过冒号与值之间的空白——手写拼接的 JSON 常带空格
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
}
