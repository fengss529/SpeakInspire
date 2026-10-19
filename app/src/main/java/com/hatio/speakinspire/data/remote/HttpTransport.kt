package com.hatio.speakinspire.data.remote

import kotlinx.coroutines.flow.Flow

/**
 * 底层传输抽象。
 *
 * 把 HTTP 从 AI 服务里剥出来，好处有两个：
 * 1. 测试可以替换传输层，不必真的发请求
 * 2. SSE 分帧逻辑只写一次，且能独立测试
 *
 * 两家服务商都用 OpenAI 兼容协议，所以一个实现就够——
 * 差异只体现在 [ProviderDialect] 的少量分支里。
 */
interface HttpTransport {

    /**
     * 发一个流式请求。
     *
     * @param body 已拼好的 JSON body（各家 thinking 参数差异已在调用方处理）
     * @return 逐帧的 SSE data 行（不含 `data: ` 前缀已剥离的原始行也返回，由 SseParser 处理）
     */
    fun stream(baseUrl: String, apiKey: String, body: String): Flow<String>

    /** 非流式请求，返回完整响应体 */
    suspend fun post(baseUrl: String, apiKey: String, body: String): String

    /** 最短请求，用于「测试连接」 */
    suspend fun ping(baseUrl: String, apiKey: String, model: String): String
}

/** 把原始 SSE 行流解析成内容片段 */
object SseParser {

    /**
     * 处理一行 SSE。
     *
     * ## 为什么必须容错 keep-alive 注释帧
     *
     * DeepSeek 和 MiMo 都会发 `: keep-alive` 这种注释帧保持连接。
     * 若按 `data:` 前缀过滤后直接当内容处理，会把脏数据喂给 UI。
     * 这类问题只在网络慢时才偶发，**极难复现**——
     * 所以这里必须有明确的测试覆盖。
     *
     * @return 内容片段；非内容帧返回 null
     */
    fun parse(line: String): String? {
        val t = line.trim()
        if (t.isEmpty()) return null
        if (ProviderDialect.isCommentFrame(t)) return null
        if (ProviderDialect.isDoneFrame(t)) return null
        return ProviderDialect.extractDelta(t)
    }

    /**
     * 这一帧里有没有非空的 `reasoning_content`。
     *
     * 思考模式开着时，模型先吐一长串思维链，正文要等很久甚至根本不来。
     * App 以前把这些帧直接丢掉，于是「一个字都没出来」——
     * 用户不知道是网络慢、模型坏了，还是思考模式没关。
     */
    fun hasReasoning(line: String): Boolean {
        val t = line.trim()
        if (!t.startsWith("data:")) return false
        val idx = t.indexOf("\"reasoning_content\"")
        if (idx < 0) return false
        val rest = t.substring(idx)
        // "reasoning_content":null 或 "" 都算没有
        return !rest.startsWith("\"reasoning_content\":null") &&
            !rest.startsWith("\"reasoning_content\": \"\"") &&
            !rest.startsWith("\"reasoning_content\":\"\"")
    }

    /**
     * 流里夹带的错误。
     *
     * 服务商在 HTTP 200 建立连接之后，仍可能在流中间发一帧
     * `data: {"error":{...}}`（额度用尽、内容审核、参数不支持都会这样）。
     * 以前这帧被当成「没有 content」丢掉，最终表现为**空回复**——
     * 一个明明有原因的失败，被藏成了一句「模型没返回问题」。
     */
    fun extractError(line: String): String? {
        val t = line.trim()
        if (!t.contains("\"error\"")) return null
        val json = if (t.startsWith("data:")) t.removePrefix("data:").trim() else t
        if (!json.contains("\"error\"")) return null
        // 空对象 {"error":null} 不算
        if (json.contains("\"error\":null") || json.contains("\"error\": null")) return null
        return json
    }
}