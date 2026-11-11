package com.hatio.speakinspire

import com.hatio.speakinspire.data.remote.NoContentReason
import com.hatio.speakinspire.data.remote.ResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 成文拿到 200 却没有正文时的归因。
 *
 * ## 为什么值得单独测
 *
 * 2026-11-11 真机：追问 5 轮后点成文，界面显示
 * 「响应里没有 content 字段」加一段被截成两行的原始 JSON。
 * 用户看到的是一坨机器话，我们看到的是一样的一坨——
 * 因为四种完全不同的失败被压成了同一句话。
 *
 * 这些 fixture 都照着服务商的真实响应形状写。哪一条变色，
 * 就说明某一种失败又退回到「混在一起」的状态了。
 */
class ComposeNoContentTest {

    private fun body(choices: String, extra: String = ""): String =
        """{"id":"x","object":"chat.completion","created":1,"model":"deepseek-flash,""" +
            """"choices":[$choices]$extra}"""

    @Test
    fun `只回了思考过程——换服务商能解决`() {
        val raw = body(
            """{"index":0,"message":{"role":"assistant","content":null,
               "reasoning_content":"我先拆一下他的问题，他说的其实是……"},
               "finish_reason":"stop"}""",
        )
        assertEquals(NoContentReason.THINKING_ONLY, ResponseParser.whyNoContent(raw))
    }

    @Test
    fun `输出到上限被截断——再点一次`() {
        val raw = body(
            """{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"length"}""",
        )
        assertEquals(NoContentReason.TRUNCATED, ResponseParser.whyNoContent(raw))
    }

    @Test
    fun `被安全策略拦下——换说法`() {
        val raw = body(
            """{"index":0,"message":{"role":"assistant","content":null},
               "finish_reason":"content_filter"}""",
        )
        assertEquals(NoContentReason.FILTERED, ResponseParser.whyNoContent(raw))
    }

    @Test
    fun `是补全响应但正文为空——再点一次`() {
        val raw = body(
            """{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"stop"}""",
        )
        assertEquals(NoContentReason.EMPTY, ResponseParser.whyNoContent(raw))
    }

    @Test
    fun `连 choices 都没有——只能把原文交给用户`() {
        val raw = """{"error":{"message":"upstream overloaded"}}"""
        assertEquals(NoContentReason.UNDETERMINED, ResponseParser.whyNoContent(raw))
    }

    @Test
    fun `finish_reason 缺字段时不能当成异常`() {
        val raw = body("""{"index":0,"message":{"role":"assistant","content":""}}""")
        assertNull(ResponseParser.finishReason(raw))
        assertEquals(NoContentReason.EMPTY, ResponseParser.whyNoContent(raw))
    }

    @Test
    fun `正文里有 length 这个词，也不会被判成截断`() {
        // 归因只看 finish_reason 字段，不grep关键字。
        // 这一条防的是「用 contains 图省事」的写法回潮——
        // 正文里出现「长度」「截断」太正常了。
        val raw = body(
            """{"index":0,"message":{"role":"assistant","content":"他说长度这件事"},
               "finish_reason":"stop"}""",
        )
        // 正文存在却来问「为什么没有正文」：两者对不上，交回原文
        assertEquals(NoContentReason.UNDETERMINED, ResponseParser.whyNoContent(raw))
    }
}
