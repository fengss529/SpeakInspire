package com.hatio.speakinspire.service

import com.hatio.speakinspire.data.model.Angle
import com.hatio.speakinspire.data.model.ArticleDraft
import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.ComposeResult
import com.hatio.speakinspire.data.model.DraftParagraph
import com.hatio.speakinspire.data.model.ErrorKindLite
import com.hatio.speakinspire.data.model.OutlineSection
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.data.model.ScriptOutline
import com.hatio.speakinspire.data.model.ShootingCue
import com.hatio.speakinspire.data.remote.ErrorMapper
import com.hatio.speakinspire.data.remote.HttpTransport
import com.hatio.speakinspire.data.remote.RequestBuilder
import com.hatio.speakinspire.data.remote.ResponseParser
import com.hatio.speakinspire.data.remote.SseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
/**
 * 真实的 AI 服务实现。
 *
 * **注意这里没有任何业务逻辑**——所有权限边界都在 [Prompts] 里，
 * 且由 `PromptBoundaryTest` 守着。这个类只负责：
 * 1. 拼请求
 * 2. 发出去
 * 3. 把响应转成模型
 *
 * 这样做的好处：出问题时能立刻分清是prompt 写错了还是传输代码错了。
 */
class RealAiService(
    private val transport: HttpTransport,
    private val configProvider: () -> ProviderConfig,
) : AiService {

    /** 追问：流式逐字。打字机效果就靠这个 */
    override fun streamQuestion(context: DeepeningContext): Flow<String> {
        val cfg = configProvider()
        val body = RequestBuilder.probe(
            cfg,
            Prompts.PROBE_SYSTEM_PROMPT,
            context.rounds,
        )
        return transport.stream(ResponseParser.endpoint(cfg.baseUrl), cfg.apiKey, body)
            .transformLines()
            .flowOn(Dispatchers.IO)
    }

    /** 成文：一次性 JSON 输出。素材不足必须拒稿 */
    override suspend fun composeArticle(context: DeepeningContext): ComposeResult {
        val cfg = configProvider()
        if (context.rounds.isEmpty()) {
            return ComposeResult.Rejected(0, ThinMaterial.THRESHOLD)
        }
        if (context.rounds.none { it.isUser }) {
            return ComposeResult.Rejected(0, ThinMaterial.THRESHOLD)
        }

        return try {
            val body = RequestBuilder.compose(
                cfg,
                Prompts.DRAFT_SYSTEM_PROMPT,
                context.rounds,
            )
            val raw = transport.post(ResponseParser.endpoint(cfg.baseUrl), cfg.apiKey, body)
            val content = ResponseParser.extractContent(raw)
                ?: return ComposeResult.Failed(ErrorKindLite.UNKNOWN)

            parseCompose(content, context.materialSize)
        } catch (e: TransportException) {
            // 把可归因的错误原样带出去。
            // 之前所有异常都归成 NETWORK——用户 key 填错时会看到
            // 「网络不可用」，然后去查 WiFi，方向完全错了
            ComposeResult.Failed(e.kind.toLite())
        } catch (e: Exception) {
            ComposeResult.Failed(ErrorKindLite.NETWORK)
        }
    }

    /** 拆角度：只要标题+理由，不要骨架（决策 Q3） */
    override suspend fun deriveAngles(article: ArticleDraft): List<Angle> {
        val cfg = configProvider()
        return try {
            val body = RequestBuilder.angles(
                cfg,
                Prompts.ANGLE_SYSTEM_PROMPT,
                buildArticleText(article),
            )
            val raw = transport.post(ResponseParser.endpoint(cfg.baseUrl), cfg.apiKey, body)
            val content = ResponseParser.extractContent(raw) ?: return emptyList()
            parseAngles(content)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 生成完整骨架：意群短语，不是逐字稿 */
    override suspend fun buildOutline(
        article: ArticleDraft,
        angle: Angle,
    ): ScriptOutline {
        val cfg = configProvider()
        return try {
            val body = RequestBuilder.outline(
                cfg,
                Prompts.OUTLINE_SYSTEM_PROMPT,
                angle.title,
            )
            val raw = transport.post(ResponseParser.endpoint(cfg.baseUrl), cfg.apiKey, body)
            val content = ResponseParser.extractContent(raw)
                ?: return ScriptOutline(angle.title, emptyList())
            parseOutline(content, angle.title)
        } catch (e: Exception) {
            ScriptOutline(angle.title, emptyList())
        }
    }

    /** 连接测试：设置页「测试连接」按钮用 */
    override suspend fun testConnection(config: ProviderConfig): ConnectionResult {
        val start = System.currentTimeMillis()
        return try {
            val raw = transport.ping(
                ResponseParser.endpoint(config.baseUrl),
                config.apiKey,
                config.model,
            )
            // 服务商返回 200 但 body 里可能有 error 字段
            if (raw.contains("\"error\"", ignoreCase = true) ||
                raw.contains("insufficient", ignoreCase = true)
            ) {
                val kind = ErrorMapper.map(200, raw)
                return ConnectionResult.Failure(kind, ErrorMapper.humanize(kind))
            }
            ConnectionResult.Success(
                latencyMs = System.currentTimeMillis() - start,
                model = config.model,
                contextWindow = null,
            )
        } catch (e: TransportException) {
            ConnectionResult.Failure(e.kind, ErrorMapper.humanize(e.kind))
        } catch (e: Exception) {
            ConnectionResult.Failure(ErrorKind.NETWORK_UNAVAILABLE, ErrorMapper.humanize(ErrorKind.NETWORK_UNAVAILABLE))
        }
    }

    // ---------- SSE 行 → 内容片段 ----------

    /**
 * SSE 行 → 内容片段。
 *
 * ## 契约：传输层每次 emit 的是**一整行**（不含换行符）
     *
     * OkHttp 的 `readUtf8Line()` 天然满足这个契约，所以这里不需要缓冲拼接。
     *
     * ## 曾经写错的地方
     *
     * 原来用 `carry` 累积「不完整的行」，结果 keep-alive 帧
     * （`: keep-alive`，解析为 null）留在 carry 里，
     * 下一帧拼上来变成 `": keep-alivedata: {...}"`，
     * 不再以 `data:` 开头，于是**后续所有帧都被丢弃**——
     * 表现为追问只显示第一句。
     *
     * 教训：无法判定「半行」时不要缓冲。这里契约明确，直接按行处理。
     */
    private fun Flow<String>.transformLines(): Flow<String> = flow {
        collect { chunk ->
            // 兼容一批里塞了多行的情况
            if (chunk.contains('\n')) {
                chunk.split('\n').forEach { line ->
                    if (line.isNotBlank()) SseParser.parse(line)?.let { emit(it) }
                }
            } else {
                SseParser.parse(chunk)?.let { emit(it) }
            }
        }
    }.catch { e ->
        if (e is TransportException) throw e
        throw TransportException(ErrorKind.MALFORMED_STREAM, e.message)
    }

    // ---------- 响应解析 ----------

    private fun parseCompose(content: String, userChars: Int): ComposeResult {
        val trimmed = stripCodeFence(content)
        if (trimmed.contains("\"rejected\"") || trimmed.contains("拒绝成稿")) {
            return ComposeResult.Rejected(userChars, ThinMaterial.THRESHOLD)
        }

        val title = extractField(trimmed, "title")?.takeIf { it.isNotBlank() }
            ?: extractFirstString(trimmed)
            ?: "未命名"

        // 逐个提取 paragraphs 数组里的 text/mine
        val parasRaw = extractArrayField(trimmed, "paragraphs")
            ?: return ComposeResult.Failed(ErrorKindLite.UNKNOWN)

        val paragraphs = splitObjects(parasRaw).mapNotNull { obj ->
            val text = extractField(obj, "text") ?: return@mapNotNull null
            val mine = extractField(obj, "isMine")?.trim() == "true"
            DraftParagraph(text, isMine = mine)
        }
        if (paragraphs.isEmpty()) return ComposeResult.Failed(ErrorKindLite.UNKNOWN)
        return ComposeResult.Success(title, paragraphs)
    }

    private fun parseAngles(content: String): List<Angle> {
        val trimmed = stripCodeFence(content)
        // 模型可能返回 {"angles":[...]} 或直接 [...]
        val arrayRaw = extractArrayField(trimmed, "angles")
            ?: trimmed.substringAfterLast('[').substringBeforeLast(']').let { "[$it]" }

        return splitObjects(arrayRaw).mapNotNull { obj ->
            val title = extractField(obj, "title") ?: return@mapNotNull null
            Angle(
                title = title,
                rationale = extractField(obj, "rationale") ?: "",
                kind = extractField(obj, "kind") ?: "",
            )
        }.take(3)  // 只要 3 个角度，这是设计约束
    }

    private fun parseOutline(content: String, angleTitle: String): ScriptOutline {
        val trimmed = stripCodeFence(content)
        val sectionsRaw = extractArrayField(trimmed, "sections")
            ?: return ScriptOutline(angleTitle, emptyList())

        val sections = splitObjects(sectionsRaw).map { obj ->
            OutlineSection(
                title = extractField(obj, "title") ?: "段落",
                seconds = extractField(obj, "seconds")?.trim()?.toIntOrNull() ?: 10,
                goal = extractField(obj, "goal") ?: "",
                phrases = extractStringArray(obj, "phrases"),
                cues = extractCues(obj),
            )
        }
        return ScriptOutline(angleTitle, sections)
    }

    private fun extractCues(obj: String): List<ShootingCue> {
        val raw = extractArrayField(obj, "cues") ?: return emptyList()
        return splitObjects(raw).mapNotNull { c ->
            val text = extractField(c, "text") ?: return@mapNotNull null
            ShootingCue(icon = extractField(c, "icon") ?: "", text = text)
        }
    }

    /**
     * 可归因错误 → 轻量错误类型（供 ComposeResult.Failed 携带）。
     *
     * 没映射的一律落到 UNKNOWN，而不是 NETWORK——
     * 「不知道」比「是网络问题」更诚实。
     */
    private fun ErrorKind.toLite(): ErrorKindLite = when (this) {
        ErrorKind.INVALID_API_KEY, ErrorKind.MODEL_NOT_FOUND -> ErrorKindLite.UNAUTHORIZED
        ErrorKind.RATE_LIMITED -> ErrorKindLite.RATE_LIMITED
        ErrorKind.NETWORK_UNAVAILABLE, ErrorKind.MALFORMED_STREAM -> ErrorKindLite.NETWORK
        else -> ErrorKindLite.UNKNOWN
    }

    /** 去掉模型可能加的 ```json 包裹 */
    private fun stripCodeFence(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        return t.substringAfter('\n', "").substringBeforeLast("```").trim()
    }

    private fun buildArticleText(article: ArticleDraft): String =
        buildString {
            appendLine("标题：${article.title}")
            appendLine()
            article.dialogue.filter { it.isUser }.forEach {
                appendLine(it.content)
            }
        }

    companion object {
        /** 素材下限，与 FakeAiService 保持一致 */
        const val THIN_MATERIAL = 300
    }
}

/** 成文素材不足时拒稿的阈值 */
object ThinMaterial {
    const val THRESHOLD = 300
}

/** 传输层异常，带可归因的错误类型 */
class TransportException(
    val kind: ErrorKind,
    message: String? = null,
) : Exception(message)