package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.remote.Providers
import com.hatio.speakinspire.service.DeepeningContext
import com.hatio.speakinspire.service.ErrorKind
import com.hatio.speakinspire.service.RealAiService
import com.hatio.speakinspire.service.TransportException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空回复必须说得出原因。
 *
 * ## 为什么需要这一层
 *
 * 以前 SSE 里解析不出 content 的帧全被丢掉，于是三种完全不同的故障
 * 在界面上长得一模一样——都是「模型没返回问题，再试一次」：
 *
 * 1. 思考模式吃光了 `max_tokens`（该去关思考）
 * 2. 流里带了 error 帧（该看原文：额度？审核？）
 * 3. 服务商推了空流（该重试或换模型）
 *
 * 用户只能反复重试，然后放弃。这个文件把三种情形分开钉住。
 */
class StreamAuditTest {

    private fun cfg() = Providers.DEEPSEEK.copy(apiKey = "sk-test")

    private fun ctx() = DeepeningContext(
        noteId = "n1",
        originalNote = "人生无常，接纳一切",
        rounds = emptyList(),
        roundIndex = 0,
        maxRounds = 5,
    )

    private fun service(t: FakeTransport) = RealAiService(t) { cfg() }

    /** 跑一次流，返回抛出的异常；没抛就是测试写错了 */
    private fun failureOf(lines: List<String>): TransportException {
        val t = FakeTransport()
        t.sseLines = lines
        try {
            runBlocking { service(t).streamQuestion(ctx()).toList() }
        } catch (e: TransportException) {
            return e
        }
        throw AssertionError("应该抛异常，实际正常结束")
    }

    // ---------- 正常路径不能被打扰 ----------

    @Test
    fun `DeepSeek 的真实帧能正常解析`() = runBlocking {
        val t = FakeTransport()
        t.sseLines = listOf(
            """data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":null}}]}""",
            """data: {"id":"1","choices":[{"index":0,"delta":{"content":"你说"}}]}""",
            """data: {"id":"1","choices":[{"index":0,"delta":{"content":"的「接纳」"}}]}""",
            """data: {"id":"1","choices":[{"index":0,"delta":{"content":"具体指什么？"}}]}""",
            """data: {"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            "data: [DONE]",
        )
        val out = service(t).streamQuestion(ctx()).toList().joinToString("")
        assertEquals("你说的「接纳」具体指什么？", out)
    }

    @Test
    fun `思考与正文都有时只要正文`() = runBlocking {
        val t = FakeTransport()
        t.sseLines = listOf(
            """data: {"choices":[{"delta":{"reasoning_content":"用户在说人生无常"}}]}""",
            """data: {"choices":[{"delta":{"reasoning_content":"我应该问他具体指什么"}}]}""",
            """data: {"choices":[{"delta":{"content":"你说的无常"}}]}""",
            """data: {"choices":[{"delta":{"content":"是哪一次？"}}]}""",
            "data: [DONE]",
        )
        val out = service(t).streamQuestion(ctx()).toList().joinToString("")
        assertEquals("你说的无常是哪一次？", out)
    }

    // ---------- 三种空回复，三种说法 ----------

    @Test
    fun `只有思考没有正文时归因为思考吃光了输出`() {
        val e = failureOf(
            listOf(
                """data: {"choices":[{"delta":{"reasoning_content":"用户在说人生无常，"}}]}""",
                """data: {"choices":[{"delta":{"reasoning_content":"我该问哪一次具体的事"}}]}""",
                """data: {"choices":[{"delta":{},"finish_reason":"length"}]}""",
                "data: [DONE]",
            )
        )
        assertEquals(ErrorKind.THINKING_ONLY, e.kind)
        assertTrue("详情里要说明收到了什么：${e.detail}", e.detail!!.contains("思考"))
    }

    @Test
    fun `流里带 error 帧时把原文带出来`() {
        val e = failureOf(
            listOf(
                """data: {"error":{"message":"Insufficient Balance","type":"insufficient_quota"}}""",
                "data: [DONE]",
            )
        )
        assertEquals(ErrorKind.STREAM_ERROR, e.kind)
        assertTrue("必须能读到服务商的原文：${e.detail}", e.detail!!.contains("Insufficient Balance"))
    }

    @Test
    fun `一帧都没有时归因为空流`() {
        val e = failureOf(emptyList())
        assertEquals(ErrorKind.NO_CONTENT, e.kind)
    }

    @Test
    fun `全是 keep-alive 注释帧也算空流`() {
        val e = failureOf(
            listOf(": keep-alive", ": keep-alive", "data: [DONE]")
        )
        assertEquals(ErrorKind.NO_CONTENT, e.kind)
    }

    @Test
    fun `全是解析不了的脏帧也要说清有几段`() {
        val e = failureOf(
            listOf(
                """data: {"choices":[{"delta":{}}]}""",
                """data: {"foo":"bar"}""",
                "data: [DONE]",
            )
        )
        assertEquals(ErrorKind.NO_CONTENT, e.kind)
        assertTrue("应提到收到的段数：${e.detail}", e.detail!!.contains("段"))
    }

    // ---------- 边界 ----------

    @Test
    fun `reasoning_content 为 null 不算思考帧`() {
        val e = failureOf(
            listOf(
                """data: {"choices":[{"delta":{"reasoning_content":null,"content":null}}]}""",
                "data: [DONE]",
            )
        )
        // null 是常态（非思考模型的每一帧都这样），不该被当成「思考吃光了输出」
        assertEquals(ErrorKind.NO_CONTENT, e.kind)
    }

    @Test
    fun `一个 chunk 里塞多行也能全部解析`() = runBlocking {
        val t = FakeTransport()
        t.sseLines = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"第一句\"}}]}\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"第二句\"}}]}",
            "data: [DONE]",
        )
        val out = service(t).streamQuestion(ctx()).toList().joinToString("")
        assertEquals("第一句第二句", out)
    }

    @Test
    fun `400 的异常不会被吞成空流`() {
        val t = FakeTransport()
        t.throwOnStream = TransportException(
            kind = ErrorKind.BAD_REQUEST,
            message = "请求被拒",
            statusCode = 400,
            detail = """{"error":{"message":"Invalid JSON"}}""",
        )
        try {
            runBlocking { service(t).streamQuestion(ctx()).toList() }
            throw AssertionError("应该抛异常")
        } catch (e: TransportException) {
            assertEquals("可归因的异常必须原样传出去", ErrorKind.BAD_REQUEST, e.kind)
            assertTrue("原文不能丢", e.detail!!.contains("Invalid JSON"))
        }
    }
}
