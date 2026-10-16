package com.hatio.speakinspire.data.remote

import com.hatio.speakinspire.service.ErrorKind

/**
 * 把HTTP 状态码与响应体翻译成**可归因**的错误。
 *
 * 三段式 key、地域绑定、模型名过时这几个坑查证时都遇到过，
 * 用户一定会填错。「测试连接」按钮是唯一能立刻定位问题的方式，
 * 所以错误必须按类型区分——调用方要按类型决定引导话术。
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

        statusCode >= 500 -> ErrorKind.UNKNOWN

        else -> ErrorKind.UNKNOWN
    }

    /** 把错误类型翻译成给用户看的人话——不能直接抛 HTTP 状态码 */
    fun humanize(kind: ErrorKind): String = when (kind) {
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

        ErrorKind.UNKNOWN ->
            "出了点问题，具体原因看不出来。"
    }
}