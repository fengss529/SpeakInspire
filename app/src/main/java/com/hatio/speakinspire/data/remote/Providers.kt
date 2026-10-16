package com.hatio.speakinspire.data.remote

import com.hatio.speakinspire.data.model.ProviderConfig

/**
 * 预置服务商。
 *
 * ## 为什么只有两家
 *
 * 查证过国内主流服务商的 OpenAI 兼容性，零配置的只有两家：
 *
 * | 服务商 | base_url | key 前缀 | 坑 |
 * |---|---|---|---|
 * | DeepSeek | api.deepseek.com | sk- | 几乎无坑，单base_url、不分地域 |
 * | 小米 MiMo | api.xiaomimimo.com/v1 | sk- | 思考模式默认开启 |
 * | 智谱 GLM | open.bigmodel.cn/api/paas/v4 | — | 有地域绑定 |
 * | Kimi | api.moonshot.cn/v1 | sk- | k2/moonshot-v1 已全线下线 |
 * | 腾讯混元 | — | — | 旧平台 9-30 已停服，教程全过期 |
 * | 百度千帆 | — | — | 三段式 key，与"填 API Key"的直觉不符 |
 * | 豆包 | — | — | Model ID / Endpoint ID 双轨制|
 * | Qwen | — | sk- | 必须先做地域选择器，否则一半用户 401 |
 *
 * 前两家进第一批，其余留到第二批——但结构上留好 [CUSTOM] 出口。
 */
object Providers {

    val DEEPSEEK = ProviderConfig(
        providerId = ProviderConfig.DEEPSEEK,
        baseUrl = "https://api.deepseek.com",
        apiKey = "",
        // 不要用 deepseek-v4-flash：那是遗留名，官方现为 deepseek-flash（V4.1-Flash）
        model = "deepseek-flash",
        // 实测 deepseek-flash 默认返回 reasoning_content 而 content 为空，
        // 追问的逐字浮现会变成空白等待——所以默认就关掉
        thinkingDisabled = true,
    )

    val MIMO = ProviderConfig(
        providerId = ProviderConfig.MIMO,
        baseUrl = "https://api.xiaomimimo.com/v1",
        apiKey = "",
        // 不要用 mimo-v2.5-pro：2026-10-21 下线，之后自动转发到 V2.6 系列
        model = "mimo-v2.6-pro",
        // MiMo 的 thinking 默认开启，必须显式 disabled——
        // 与 DeepSeek「换模型名控制」的心智模型正好相反
        thinkingDisabled = true,
    )

    val CUSTOM = ProviderConfig(
        providerId = ProviderConfig.CUSTOM,
        baseUrl = "",
        apiKey = "",
        model = "",
        thinkingDisabled = false,
    )

    val presets = listOf(DEEPSEEK, MIMO)

    fun byId(id: String): ProviderConfig = when (id) {
        ProviderConfig.DEEPSEEK -> DEEPSEEK
        ProviderConfig.MIMO -> MIMO
        else -> CUSTOM
    }
}

/**
 * 服务商特有的请求差异。
 *
 * 两家的心智模型正好相反，这是最容易写错的地方：
 * - DeepSeek：思考是**换模型名**（`deepseek-reasoner`），非思考模型不用额外参数
 * - MiMo：思考是**请求参数**（`thinking: {"type":"disabled"}`），默认开启
 */
object ProviderDialect {

    /** DeepSeek 的非思考模型走v4-pro / flash 系列，不需要 thinking 参数 */
    /**
     * DeepSeek 与 MiMo 都支持 `thinking: {"type":"disabled"}`。
     *
     * 实测（2026-10-16，deepseek-flash）：
     * - 不发thinking → 返回 reasoning_content，content 为空，首字延迟高
     * - 发 disabled → reasoning_content 为 null，content 直接有值
     *
     * 之前记的「DeepSeek 靠换模型名控制」是**错的**——
     * 它同样接受这个参数，且必须显式关闭，
     * 否则追问的逐字浮现会变成漫长的空白等待。
     */
    fun needsThinkingParam(cfg: ProviderConfig): Boolean =
        cfg.providerId == ProviderConfig.DEEPSEEK ||
            cfg.providerId == ProviderConfig.MIMO ||
            cfg.providerId == ProviderConfig.CUSTOM

    fun thinkingBody(cfg: ProviderConfig): String =
        if (cfg.thinkingDisabled) "\"thinking\":{\"type\":\"disabled\"},"
        else ""

    /**
     * SSE 解析必须容错 `: keep-alive` 注释帧。
     * MiMo 和 DeepSeek 都会发这种帧，直接按行split 会拿到脏数据。
     */
    fun isCommentFrame(line: String): Boolean =
        line.trimStart().startsWith(":")

    fun isDoneFrame(line: String): Boolean =
        line.trim() == "data: [DONE]"

    /** 抽delta 里的 content，容错 keep-alive 与空帧 */
    fun extractDelta(line: String): String? {
        val t = line.trim()
        if (!t.startsWith("data:")) return null
        if (isDoneFrame(t)) return null
        val json = t.removePrefix("data:").trim()
        if (json.isEmpty()) return null
        // 最小解析：只取 content 字段，避免引入完整 JSON 依赖
        val key = "\"content\":\""
        val idx = json.indexOf(key)
        if (idx < 0) return null
        val start = idx + key.length
        val sb = StringBuilder()
        var i = start
        while (i < json.length) {
            val ch = json[i]
            when {
                ch == '\\' && i + 1 < json.length -> {
                    val esc = json[i + 1]
                    sb.append(
                        when (esc) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'u' -> {
                                val hex = json.substring(i + 2, i + 6)
                                i += 4
                                hex.toIntOrNull(16)?.toChar() ?: '?'
                            }
                            else -> esc
                        }
                    )
                    i += 2
                }
                ch == '"' -> return sb.toString()
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        return null
    }
}