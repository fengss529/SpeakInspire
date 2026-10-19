package com.hatio.speakinspire.data.remote

import com.hatio.speakinspire.service.ErrorKind

/**
 * 把HTTP 状态码与响应体翻译成**可归因**的错误。
 *
 * 三段式 key、地域绑定、模型名过时这几个坑查证时都遇到过，
 * 用户一定会填错。「测试连接」按钮是唯一能立刻定位问题的方式，
 * 所以错误必须按类型区分——调用方要按类型决定引导话术。
 *
 * ## 比分类更重要的是「说得出原话」
 *
 * 分类再细，也总有分不进去的情况。这时候如果只显示
 * 「出了点问题，具体原因看不出来」，用户和开发者都无从下手——
 * 用户只能重试，重试还是错，然后就放弃了。
 *
 * 所以每个错误都尽量带上服务商返回的**原文片段**
 * （[humanize] 的 detail 参数）。拼不上也说不上话的时候，
 * 至少让他能截图说出发生了什么。
 */
object ErrorMapper {

    fun map(statusCode: Int, body: String): ErrorKind = when {
        statusCode == 401 -> {
            // MiMo 的 key 前缀不同：sk- 走按量端点，tp- 走 Token Plan 端点。
            // 拿 Token Plan 的 key 请求按量端点会得到 401，但原因不是 key 无效。
            if (body.contains("token") || body.contains("plan", ignoreCase = true))
                ErrorKind.WRONG_ENDPOINT_FOR_KEY
            else
                ErrorKind.INVALID_API_KEY
        }

        statusCode == 402 || body.contains("insufficient", ignoreCase = true) ->
            ErrorKind.INSUFFICIENT_BALANCE

        statusCode == 403 ->
            if (body.contains("region", ignoreCase = true) ||
                body.contains("region_not_allowed", ignoreCase = true)
            ) ErrorKind.WRONG_ENDPOINT_FOR_KEY
            else ErrorKind.INVALID_API_KEY

        statusCode == 404 ->
            if (body.contains("model", ignoreCase = true))
                ErrorKind.MODEL_NOT_FOUND
            else ErrorKind.UNKNOWN

        statusCode == 429 -> ErrorKind.RATE_LIMITED

        // 400/422 是「请求被拒」，原文里通常写得很清楚
        statusCode == 400 || statusCode == 422 -> ErrorKind.BAD_REQUEST

        statusCode >= 500 -> ErrorKind.UNKNOWN

        else -> ErrorKind.UNKNOWN
    }

    /**
     * 把错误类型翻译成给用户看的人话。
     *
     * [detail] 是服务商返回的原文，会截断后附在末尾。
     * 有它的价值不是「更专业」，而是**能被转述**——
     * 用户截图发过来，一眼就知道哪里错了，不用再猜一轮。
     */
    fun humanize(kind: ErrorKind, detail: String? = null): String {
        val base = when (kind) {
            ErrorKind.INVALID_API_KEY ->
                "Key 无效或已过期。检查是否复制完整，有没有多余空格。"

            ErrorKind.MODEL_NOT_FOUND ->
                "模型名不存在。这个名称可能已经下线了——DeepSeek 用 deepseek-flash 而不是 deepseek-v4-flash。"

            ErrorKind.RATE_LIMITED ->
                "请求太频繁了。等几秒再试。"

            ErrorKind.INSUFFICIENT_BALANCE ->
                "余额不足。去服务商后台充值。"

            ErrorKind.WRONG_ENDPOINT_FOR_KEY ->
                "Key 和端点不匹配。小米 MiMo 的按量 key（sk-）和 Token Plan key（tp-）走不同端点；另外确认没有选错地域。"

            ErrorKind.NETWORK_UNAVAILABLE ->
                "连不上服务商。检查网络。"

            ErrorKind.MALFORMED_STREAM ->
                "返回格式不对，可能是服务商那边的问题。"

            ErrorKind.BAD_REQUEST ->
                "请求被服务商拒了。这通常是 App 这边的请求写错了，不是你填错了。"

            ErrorKind.UNKNOWN ->
                "出了点问题。"
        }
        val snippet = detail?.let { readable(it) }
        return if (snippet.isNullOrBlank()) base else "$base\n详情：$snippet"
    }

    /**
     * 把服务商的错误 JSON 压成一行人能看的话。
     *
     * 只取 message 字段——整段 JSON 塞进气泡里没人看得下去。
     * 取不到就原样截断。
     */
    private fun readable(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val msg = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(t)?.groupValues?.get(1)
            ?.replace("\\n", " ")
            ?.replace("\\\"", "\"")
            ?.trim()
        val text = msg ?: t.replace(Regex("\\s+"), " ")
        return if (text.length <= 160) text else text.take(157) + "…"
    }
}
