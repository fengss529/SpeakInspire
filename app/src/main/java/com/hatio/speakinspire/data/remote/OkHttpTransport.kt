package com.hatio.speakinspire.data.remote

import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.service.TransportException
import com.hatio.speakinspire.service.ErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp 传输实现。
 *
 * 只做三件事：发请求、读响应、判错误。
 * **不解析业务结构**——那属于 RealAiService 的职责。
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : HttpTransport {

    override fun stream(baseUrl: String, apiKey: String, body: String): Flow<String> = flow {
        val request = buildRequest(baseUrl, apiKey, body, stream = true)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                val kind = ErrorMapper.map(response.code, errBody)
                // 状态码和原文一起带出去——界面才有话可说
                throw TransportException(
                    kind = kind,
                    message = ErrorMapper.humanize(kind, errBody),
                    statusCode = response.code,
                    detail = errBody,
                )
            }
            val source = response.body?.source() ?: throw TransportException(ErrorKind.MALFORMED_STREAM)
            while (!source.exhausted()) {
                val line = source.readUtf8LineStrict()
                    ?: throw TransportException(ErrorKind.MALFORMED_STREAM, "SSE 流意外结束")
                emit(line)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun post(baseUrl: String, apiKey: String, body: String): String =
        withContext(Dispatchers.IO) {
            val request = buildRequest(baseUrl, apiKey, body, stream = false)
            executeBlocking(request)
        }

    override suspend fun ping(baseUrl: String, apiKey: String, model: String): String =
        withContext(Dispatchers.IO) {
            // 复用 RequestBuilder 的构造，避免两处定义漂移
            val body = RequestBuilder.ping(
                ProviderConfig("", baseUrl, apiKey, model)
            )
            val request = buildRequest(baseUrl, apiKey, body, stream = false)
            executeBlocking(request)
        }

    private fun executeBlocking(request: Request): String {
        try {
            client.newCall(request).execute().use { response ->
                val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                if (!response.isSuccessful) {
                    val kind = ErrorMapper.map(response.code, text)
                    throw TransportException(
                        kind = kind,
                        message = ErrorMapper.humanize(kind, text),
                        statusCode = response.code,
                        detail = text,
                    )
                }
                return text
            }
        } catch (e: IOException) {
            throw TransportException(ErrorKind.NETWORK_UNAVAILABLE, e.message)
        }
    }

    private fun buildRequest(
        baseUrl: String,
        apiKey: String,
        body: String,
        stream: Boolean,
    ): Request {
        // 有些服务商不接受 Accept: text/event-stream，改成只发必要头
        val builder = Request.Builder()
            .url(baseUrl)
            .post(body.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
        if (stream) {
            builder.header("Accept", "text/event-stream")
            // 追问是逐字浮现的，长超时才有可读性
            builder.header("Cache-Control", "no-cache")
        }
        return builder.build()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // 流式不能设太短 readTimeout——模型思考可能较久
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}