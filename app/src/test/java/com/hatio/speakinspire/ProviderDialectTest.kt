package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.data.remote.ProviderDialect
import com.hatio.speakinspire.data.remote.Providers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务商差异化处理测试。
 *
 * 这里每一条断言都对应一个**实际查证中遇到的坑**——
 * 不是为了覆盖率写的，是怕重犯。
 */
class ProviderDialectTest {

    // ---------- 预设默认值 ----------

    @Test
    fun `DeepSeek 默认模型不是已下线的遗留名`() {
        assertEquals("deepseek-flash", Providers.DEEPSEEK.model)
        assertFalse(
            "deepseek-v4-flash 是遗留名，官方现为 deepseek-flash",
            Providers.DEEPSEEK.model.contains("v4-flash"),
        )
    }

    @Test
    fun `MiMo 默认模型避开即将下线的版本`() {
        assertEquals("mimo-v2.6-pro", Providers.MIMO.model)
        assertFalse(
            "mimo-v2.5-pro 于 2026-10-21 下线",
            Providers.MIMO.model.contains("v2.5-pro"),
        )
    }

    @Test
    fun `两家都是零配置无需地域选择`() {
        // 预置服务商不填也能用，只需要key
        assertEquals("https://api.deepseek.com", Providers.DEEPSEEK.baseUrl)
        assertEquals("https://api.xiaomimimo.com/v1", Providers.MIMO.baseUrl)
    }

    // ---------- thinking 控制的两种心智模型 ----------

    @Test
    fun `MiMo 需要显式关闭思考而 DeepSeek 不需要`() {
        assertTrue("MiMo 需要 thinking 参数", ProviderDialect.needsThinkingParam(Providers.MIMO))
        assertFalse(
            "DeepSeek 靠换模型名控制，不需要 thinking 参数",
            ProviderDialect.needsThinkingParam(Providers.DEEPSEEK),
        )
    }

    @Test
    fun `两家预设的 thinkingDisabled 预设值不同`() {
        assertTrue("MiMo 默认开启，必须预设为关闭", Providers.MIMO.thinkingDisabled)
        assertFalse(
            "DeepSeek 非思考模型本身无需该参数",
            Providers.DEEPSEEK.thinkingDisabled,
        )
    }

    @Test
    fun `thinkingBody 在关闭时输出正确的 JSON 片段`() {
        val body = ProviderDialect.thinkingBody(Providers.MIMO)
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(body.endsWith(","))
    }

    @Test
    fun `thinkingBody 在不需关闭时为空`() {
        assertEquals("", ProviderDialect.thinkingBody(Providers.DEEPSEEK))
    }

    // ---------- SSE 解析：必须容错 keep-alive 注释帧 ----------

    @Test
    fun `识别 keep-alive 注释帧`() {
        assertTrue(ProviderDialect.isCommentFrame(": keep-alive"))
        assertTrue(ProviderDialect.isCommentFrame(":ping"))
        assertTrue(ProviderDialect.isCommentFrame("   : keep-alive"))
        assertFalse(ProviderDialect.isCommentFrame("data: {}"))
    }

    @Test
    fun `识别结束帧`() {
        assertTrue(ProviderDialect.isDoneFrame("data: [DONE]"))
        assertTrue(ProviderDialect.isDoneFrame("  data: [DONE]  "))
        assertFalse(ProviderDialect.isDoneFrame("data: {\"a\":1}"))
    }

    @Test
    fun `抽取 delta 中的 content`() {
        assertEquals(
            "你好",
            ProviderDialect.extractDelta("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}"),
        )
    }

    @Test
    fun `keep-alive 帧抽取结果为 null 而非脏数据`() {
        // 这是最容易写出 bug 的地方：直接 split 会把注释帧当内容
        assertNull(ProviderDialect.extractDelta(": keep-alive"))
        assertNull(ProviderDialect.extractDelta(":"))
    }

    @Test
    fun `空帧与结束帧返回 null`() {
        assertNull(ProviderDialect.extractDelta("data: "))
        assertNull(ProviderDialect.extractDelta("data: [DONE]"))
        assertNull(ProviderDialect.extractDelta(""))
        assertNull(ProviderDialect.extractDelta("event: message"))
    }

    @Test
    fun `无 content 字段时返回 null`() {
        // 首包常只有 role 而无 content
        assertNull(
            ProviderDialect.extractDelta("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"),
        )
    }

    @Test
    fun `正确处理转义字符`() {
        assertEquals(
            "说\"话\"\n换行",
            ProviderDialect.extractDelta(
                "data: {\"delta\":{\"content\":\"说\\\"话\\\"\\n换行\"}}"
            ),
        )
    }

    @Test
    fun `正确处理中文转义`() {
        assertEquals(
            "思辨",
            ProviderDialect.extractDelta("data: {\"delta\":{\"content\":\"\\u601d\\u8fa8\"}}"),
        )
    }

    @Test
    fun `流式拼接后等于完整文本`() {
        val frames = listOf(
            "data: {\"delta\":{\"content\":\"我\"}}",
            ": keep-alive",
            "data: {\"delta\":{\"content\":\"不是\"}}",
            "data: {\"delta\":{\"role\":\"assistant\"}}",
            "data: {\"delta\":{\"content\":\"念稿\"}}",
            "data: [DONE]",
        )
        val text = frames.mapNotNull { ProviderDialect.extractDelta(it) }.joinToString("")
        assertEquals("我不是念稿", text)
    }
}