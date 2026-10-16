package com.hatio.speakinspire

import com.hatio.speakinspire.data.remote.ErrorMapper
import com.hatio.speakinspire.service.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误归因测试。
 *
 * 这些都是用户一定会填错的地方——设置页的「测试连接」
 * 若给不出准确原因，就形同虚设。
 */
class ErrorMapperTest {

    @Test
    fun `401 默认为 key 无效`() {
        assertEquals(
            ErrorKind.INVALID_API_KEY,
            ErrorMapper.map(401, "Authentication Fails"),
        )
    }

    @Test
    fun `401 但提到 token 时归因为端点不匹配`() {
        // MiMo 的真实坑：Token Plan 的 key（tp-）走按量端点会401，
        // 但原因不是 key 无效，用户会一直换key
        assertEquals(
            ErrorKind.WRONG_ENDPOINT_FOR_KEY,
            ErrorMapper.map(401, "invalid token for this endpoint"),
        )
    }

    @Test
    fun `403 提到地域时归因为端点不匹配`() {
        assertEquals(
            ErrorKind.WRONG_ENDPOINT_FOR_KEY,
            ErrorMapper.map(403, "region_not_allowed"),
        )
    }

    @Test
    fun `403 未提地域时按 key 无效处理`() {
        assertEquals(
            ErrorKind.INVALID_API_KEY,
            ErrorMapper.map(403, "Forbidden"),
        )
    }

    @Test
    fun `404 且提到 model 时归因为模型名不存在`() {
        assertEquals(
            ErrorKind.MODEL_NOT_FOUND,
            ErrorMapper.map(404, "Model Not Exist"),
        )
    }

    @Test
    fun `429 归因为限流`() {
        assertEquals(ErrorKind.RATE_LIMITED, ErrorMapper.map(429, ""))
    }

    @Test
    fun `余额不足可被识别`() {
        assertEquals(
            ErrorKind.INSUFFICIENT_BALANCE,
            ErrorMapper.map(402, ""),
        )
        assertEquals(
            ErrorKind.INSUFFICIENT_BALANCE,
            ErrorMapper.map(400, "insufficient balance"),
        )
    }

    @Test
    fun `5xx 归为未知`() {
        assertEquals(ErrorKind.UNKNOWN, ErrorMapper.map(500, ""))
        assertEquals(ErrorKind.UNKNOWN, ErrorMapper.map(503, ""))
    }

    // ---------- 人话话术 ----------

    @Test
    fun `每种错误都有可执行的话术`() {
        ErrorKind.entries.forEach { kind ->
            val msg = ErrorMapper.humanize(kind)
            assertTrue("$kind 缺话术", msg.isNotBlank())
            assertTrue("$kind 话术不该直接抛状态码", !msg.contains("HTTP"))
        }
    }

    @Test
    fun `模型名过时的话术直接给出正确名称`() {
        val msg = ErrorMapper.humanize(ErrorKind.MODEL_NOT_FOUND)
        assertTrue("应直接给出可用模型名", msg.contains("deepseek-flash"))
    }

    @Test
    fun `端点不匹配的话术点明 MiMo 的 key 前缀差异`() {
        val msg = ErrorMapper.humanize(ErrorKind.WRONG_ENDPOINT_FOR_KEY)
        assertTrue("应点明 sk- 与 tp- 的区别", msg.contains("sk-") && msg.contains("tp-"))
    }

    @Test
    fun `key 无效的话术提示检查空格`() {
        // 用户最常见的错误是复制时带了空格或换行
        assertTrue(ErrorMapper.humanize(ErrorKind.INVALID_API_KEY).contains("空格"))
    }
}