package com.hatio.speakinspire.data.remote

import com.hatio.speakinspire.data.local.extractRawValue
import com.hatio.speakinspire.data.local.unquoteIfNeeded
import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.data.model.Sprout

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

    /**
     * 追问：流式，且要模型只提问不给答案。
     *
     * ## 思考模式在这里无条件关掉
     *
     * 追问要的是**一个问题**，最多两句话。思考模式对这件事没有任何帮助，
     * 但它有两个确定的坏处：
     *
     * 1. 首字延迟从「立刻」变成「等十几秒」，而这段时间用户什么都看不到；
     * 2. `max_tokens` 会被思维链先吃光，正文一个字都不来——**
     *    表现就是「点了追问没有反应」**。
     *
     * 所以这里不看 `cfg.thinkingDisabled`。那个开关的默认值取决于历史配置、
     * 用户有没有点过、旧版本的写入，太不可靠——一个用于「省点时间」的选项，
     * 不该有能力让核心功能整个失效。
     */
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
        // 200 tokens 只够「已经关掉思考」的前提。留出余量，
        // 让万一没关掉时也还能挤出正文，而不是全被算力吃空。
        maxTokens = 1024,
        forceNoThinking = true,
    )

    /**
     * 成文：一次性，要求返回 JSON，带 isMine 标注。
     *
     * 和 [probe] 一样强制关掉思考：思考模式下模型倾向于在思维链里把话说完，
     * 正文只剩一个 `{}` 或干脆不遵守 `response_format`。
     * 成文要的是结构化的 JSON，不是推理过程。
     */
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
        forceNoThinking = true,
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

    /**
     * 生成骨架：一次性。
     *
     * [articleText] 必须带上。曾经这里只发了一句「切入角度：xxx」——
     * 模型手里没有任何素材，只能编，或者干脆返回空，
     * 界面显示「共 0 段 · 大约 0 秒」。
     *
     * [sprouts] 是这条记录已经长出来的芽。带上它们，
     * 素材区就能优先复用用户看过、认过的连接——
     * 而不是每转一次选题就重新编一套互不相干的东西。
     */
    fun outline(
        cfg: ProviderConfig,
        systemPrompt: String,
        angle: String,
        articleText: String,
        sprouts: List<Sprout> = emptyList(),
        model: String? = null,
    ): String = chatBody(
        cfg = cfg,
        systemPrompt = systemPrompt,
        messages = listOf(
            ChatMessage.user(
                buildString {
                    append("文章内容：\n").append(articleText).append("\n\n")
                    append("选定的拍摄角度：").append(angle)
                    if (sprouts.isNotEmpty()) {
                        append("\n\n【他自己长过的芽】")
                        append("（有出处的优先复用，那是他看过、认过的）\n")
                        sprouts.forEachIndexed { i, sp ->
                            append("${i + 1}. ").append(sp.title).append('\n')
                            sp.sections.forEach { sec ->
                                if (sec.insight.isNotBlank()) {
                                    append("   - ").append(sec.insight.trim()).append('\n')
                                }
                                if (sec.hasSource) {
                                    append("     出处：").append(sec.source.trim()).append('\n')
                                }
                            }
                        }
                    }
                }
            ),
        ),
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
     * 发芽：一次性，返回 JSON 报告。
     *
     * 温度给到 0.85——发芽要的是**联想**，不是准确复述。
     * 太低会让三节都在同义反复，那就不叫「长出新的连接」了。
     *
     * 思考模式仍然强制关掉，理由和 [compose] 一样：
     * 开着时模型会把话全说在思维链里，正文只剩一个 `{}`。
     */
    fun sprout(
        cfg: ProviderConfig,
        systemPrompt: String,
        input: String,
        model: String? = null,
    ): String = chatBody(
        cfg = cfg,
        systemPrompt = systemPrompt,
        messages = listOf(ChatMessage.user(input)),
        model = model,
        stream = false,
        temperature = 0.85,
        jsonMode = true,
        forceNoThinking = true,
    )

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
        forceNoThinking: Boolean = false,
    ): String = buildString {
        append("{")
        append("\"model\":").append(jsonString(model ?: cfg.model)).append(",")
        append(thinkingPrefix(cfg, forceNoThinking))
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
     * [force] 为 true 时**预置服务商**无条件关闭，不看配置——追问必须这样，
     * 理由见 [probe] 的注释。
     *
     * 自定义服务商不强塞：谁也不知道对面支不支持这个参数，
     * 发一个它不认识的字段可能直接被 400 拒掉。那里仍按用户配置走。
     */
    private fun thinkingPrefix(cfg: ProviderConfig, force: Boolean): String {
        if (!ProviderDialect.needsThinkingParam(cfg)) return ""
        val close = cfg.thinkingDisabled ||
            (force && cfg.providerId != ProviderConfig.CUSTOM)
        return if (close) "\"thinking\":{\"type\":\"disabled\"}," else ""
    }

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
 * 扫描逻辑复用 [com.hatio.speakinspire.data.local.extractRawValue]：
 * 这里以前还挂着第三份 `extractRawValue` + `unquote` 手写副本，
 * 与 SimpleJson 里那份加起来一共三处同构代码，现已合并到 JsonScan。
 */
object ResponseParser {

    /**
     * 抽出非流式响应里的 content。
     *
     * 判断「这个值能不能当正文」必须看**原始值**的第一个字符：
     * 只有字符串字面量（以 `"` 开头）才是正文。
     * 曾经写成「解完引号再判断是否以 `{` 开头」——那条判断会误伤所有
     * JSON 模式的回复（成文、拆角度、骨架的 content 本身就是 `{...}` 字符串），
     * 表现为模型正常返回但整篇解析为空。
     */
    fun extractContent(body: String): String? {
        // 优先 message.content，其次 choices[0].text（兼容部分服务商）
        for (key in listOf("content", "text")) {
            val raw = extractRawValue(body, "\"$key\":") ?: continue
            if (!raw.trimStart().startsWith("\"")) continue
            val v = unquoteIfNeeded(raw)
            if (!v.isNullOrBlank()) return v
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
}
