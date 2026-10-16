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
}