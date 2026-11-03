package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页状态规则测试。
 *
 * 重点是「换服务商要清 key」——否则用户会把 A 家的 key 发给 B 家，
 * 这种事发生了很难自己发现。
 */
class SettingsUiStateTest {

    private fun state(
        provider: String = ProviderConfig.DEEPSEEK,
        apiKey: String = "",
        keySaved: Boolean = false,
    ) = SettingsUiState(
        selectedProvider = provider,
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-flash",
        apiKey = apiKey,
        keyAlreadySaved = keySaved,
    )

    // ---------- 自定义才要手填 ----------

    @Test
    fun `预置服务商不需要手填地址`() {
        assertFalse(state(ProviderConfig.DEEPSEEK).needsManualInput)
        assertFalse(state(ProviderConfig.MIMO).needsManualInput)
    }

    @Test
    fun `自定义需要手填地址与模型`() {
        assertTrue(state(ProviderConfig.CUSTOM).needsManualInput)
    }

    // ---------- 换服务商清 key ----------

    @Test
    fun `预置服务商显示拿 key 的指引`() {
        val ds = state(ProviderConfig.DEEPSEEK).keyHint
        assertTrue("要告诉用户去哪拿 key", ds.contains("deepseek"))
        val mimo = state(ProviderConfig.MIMO).keyHint
        assertTrue(mimo.contains("MiMo"))
    }

    @Test
    fun `自定义服务商的提示是通用的`() {
        assertTrue(state(ProviderConfig.CUSTOM).keyHint.contains("API Key"))
    }

    @Test
    fun `已保存的 key 用掩码提示而非明文`() {
        val s = state(keySaved = true)
        assertTrue(s.keyAlreadySaved)
    }

    // ---------- 转换 ----------

    @Test
    fun `转成配置时字段完整`() {
        val s = state(apiKey = "sk-abc123")
        val cfg = s.toConfig()
        assertEquals(ProviderConfig.DEEPSEEK, cfg.providerId)
        assertEquals("sk-abc123", cfg.apiKey)
        assertEquals("deepseek-flash", cfg.model)
        assertEquals("https://api.deepseek.com", cfg.baseUrl)
    }

    @Test
    fun `预置服务商一律关掉思考模式`() {
        // 追问和成文都在请求层强制关；这里存的值只是别让它被界面改乱。
        // 思考模式不是选项，是这条链路的固定条件
        assertTrue(state(ProviderConfig.DEEPSEEK).toConfig().thinkingDisabled)
        assertTrue(state(ProviderConfig.MIMO).toConfig().thinkingDisabled)
    }

    @Test
    fun `自定义服务商不强塞思考参数`() {
        // 对面认不认这个字段谁也不知道，发过去可能直接 400
        assertFalse(state(ProviderConfig.CUSTOM).toConfig().thinkingDisabled)
    }

    @Test
    fun `默认模型名不是过时的`() {
        // deepseek-v4-flash / mimo-v2.5-pro 都已下线
        assertFalse(state(ProviderConfig.DEEPSEEK).model.contains("v4-flash"))
        assertFalse(state(ProviderConfig.MIMO).model.contains("v2.5"))
    }

    @Test
    fun `初始状态没有测试结果`() {
        assertEquals(null, state().testResult)
    }

    @Test
    fun `初始状态未在测试中`() {
        assertFalse(state().testing)
    }
}