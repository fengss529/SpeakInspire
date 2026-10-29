package com.hatio.speakinspire.service

import com.hatio.speakinspire.data.local.extractArrayField
import com.hatio.speakinspire.data.local.extractField
import com.hatio.speakinspire.data.local.extractFirstString
import com.hatio.speakinspire.data.local.extractStringArray
import com.hatio.speakinspire.data.local.splitObjects
import com.hatio.speakinspire.data.model.Angle
import com.hatio.speakinspire.data.model.ArticleDraft
import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.ComposeResult
import com.hatio.speakinspire.data.model.DraftParagraph
import com.hatio.speakinspire.data.model.ErrorKindLite
import com.hatio.speakinspire.data.model.Material
import com.hatio.speakinspire.data.model.MaterialKind
import com.hatio.speakinspire.data.model.OutlineSection
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.data.model.ScriptOutline
import com.hatio.speakinspire.data.model.ShootingCue
import com.hatio.speakinspire.data.model.Sprout
import com.hatio.speakinspire.data.model.SproutSection
import com.hatio.speakinspire.domain.MaterialKit
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

    /**
     * 追问：流式逐字。打字机效果就靠这个。
     *
     * ## 第一问的请求里必须带原始记录
     *
     * 曾经这里只发 `context.rounds`。第一问时 rounds 是空的，
     * 请求体里就只剩一条 system message——模型手里没有任何素材，
     * 于是「提不出问题来」，页面停在空白。
     *
     * 拼消息时把 [DeepeningContext.originalNote] 固定成第一条 user 消息，
     * 后续每轮都带着它。多花几十个 token，换来的是模型始终知道
     * 「用户最初说的到底是什么」——追问跑到第四轮的时候，
     * 这个锚点尤其重要。
     */
    override fun streamQuestion(context: DeepeningContext): Flow<String> {
        val cfg = configProvider()
        // 已经问过的问题要明写进 prompt。
        //
        // 模型手里本来就有完整历史，但它倾向于围着最新那句再追一层，
        // 越问越窄。把问过的句子单列出来并禁止换词重问，
        // 它才会回到整条记录里找别的入口。
        val asked = context.rounds.filterNot { it.isUser }.map { it.content }
        val body = RequestBuilder.probe(
            cfg,
            Prompts.probeSystem(
                method = context.probeMethod,
                roundIndex = context.roundIndex,
                maxRounds = context.maxRounds,
                askedQuestions = asked,
                methodInstruction = context.probeInstruction,
            ),
            probeMessages(context),
        )
        return flow {
            val audit = StreamAudit()
            transport.stream(ResponseParser.endpoint(cfg.baseUrl), cfg.apiKey, body)
                .parseContent(audit)
                .collect { emit(it) }
            // 流正常结束却一个字都没有——这里必须抛，不能默默返回空流。
            // 返回空流的话，界面上只剩「模型没返回问题」，
            // 而真实原因（思考吃光了额度 / 流里带着 error）就藏没了。
            audit.raiseIfNothing()
        }.flowOn(Dispatchers.IO)
    }

    /**
     * 追问的消息序列：原始记录 + 已发生的问答。
     *
     * 顺序是 user(想法) → assistant(问题) → user(回答) → ……
     * 这是标准的多轮对话形状，模型不会弄混谁说了什么。
     */
    private fun probeMessages(context: DeepeningContext): List<ChatMessage> {
        val note = context.originalNote.trim()
        val opening = if (note.isEmpty()) {
            "我想聊一件事，但还没想清楚。"
        } else {
            "我随手记下的想法：\n$note"
        }
        return buildList {
            add(ChatMessage.user(opening))
            addAll(context.rounds)
        }
    }

    /**
     * 成文的消息序列。
     *
     * ## 顺序：原始记录 → 已有问答 → 发芽 → 明确指令
     *
     * 最后一条必须是用户，理由见下方注释。
     *
     * ## 三个开关是用户的选择，不是优化
     *
     * 成文页顶部有三个可勾芯片（记录原文 / 追问 N 轮 / 发芽 N 颗），
     * 默认全选。取消勾选意味着「这一段我不想让它进文章」——
     * 尤其是发芽：里面装着别人的话，有人不愿意它们混进自己的东西里。
     *
     * 所以这里按开关拼，**不是**先全发再让模型忽略。
     * 「我发过去了但它没采用」和「我根本没发」是两件事，
     * 用户没法验证前者，但能验证后者。
     *
     * ## 为什么原始记录要放回来
     *
     * 之前只发 `context.rounds`。可用户最初那句随手记**根本不在 rounds 里**——
     * 那是 note 表的内容。于是模型只看到「问题 + 回答」，
     * 而回答常常是「嗯，就是那个」这种要靠上文才成立的句子。
     * 更要紧的是：素材本来就少的人，反过来又少了开头那一大段。
     *
     * [DeepeningContext.materialSize] 既然把原始记录算进素材，
     * 这里就必须真的发出去，否则「够字数了」和「模型手里有货」
     * 是两件事，成文质量会莫名其妙地差。
     *
     * ## 为什么最后一条必须是用户
     *
     * 追问常常停在「AI 刚问完、用户还没答」的位置。
     * 这时候直接拿 rounds 拍平，`messages` 的最后一条是 `assistant`——
     * 而 `response_format: json_object` 要求结尾是用户消息：
     * 否则模型会把最后那句问题当成「要接着续写的话」，
     * 而不是执行「写成文章」这件事。结果要么被拒，要么吐一段非 JSON。
     *
     * 所以末尾补一条明确的指令。它同时解决另一件事：
     * 模型不必猜「现在该干什么」——系统提示里说要成文，
     * 但对话看起来还在进行中。
     */
    private fun composeMessages(context: DeepeningContext): List<ChatMessage> = buildList {
        val note = context.originalNote.trim()
        // 三个都关掉等于让它凭空写。界面不许取消最后一项
        // （见 ComposeViewModel.toggle），这里再兜一次：
        // 宁可多发一点，也不要让模型在一片空白上编
        val noneChosen = !context.includeNote &&
            !context.includeDialogue &&
            !context.includeSprouts

        if (note.isNotEmpty() && (context.includeNote || noneChosen)) {
            add(ChatMessage.user("我随手记下的想法：\n$note"))
        }
        if (context.includeDialogue || noneChosen) {
            addAll(context.rounds)
        }
        if (context.includeSprouts && context.sprouts.isNotEmpty()) {
            add(ChatMessage.user(sproutBlock(context.sprouts)))
        }
        if (lastOrNull()?.isUser != true) {
            add(ChatMessage.user("按上面的要求，把我说过的话整理成文章。只输出 JSON。"))
        }
    }

    /**
     * 发芽材料块。
     *
     * 把三样东西都摊开：他说过的种子、长出来的连接、出处。
     * **出处必须一起发**——只给 insight 而藏掉 source，模型会把它
     * 当成「用户可以自称观点」的东西，然后写成 isMine = true。
     */
    private fun sproutBlock(sprouts: List<Sprout>): String = buildString {
        appendLine("【他自己长过的芽】")
        appendLine("这些是他在 App 里看过、认可过的连接。里面混着别人的说法、例子、引用。")
        appendLine("凡是用了这里面东西的段落：isMine = false，fromSprout = true。")
        appendLine("原文没有出处的那几条，写进文章时也不许替它补一个出处。")
        appendLine()
        sprouts.forEachIndexed { index, sprout ->
            appendLine("${index + 1}. ${sprout.title}")
            sprout.sections.forEach { sec ->
                if (sec.seed.isNotBlank()) appendLine("   你说的：${sec.seed.trim()}")
                appendLine("   连接：${sec.insight.trim()}")
                if (sec.hasSource) appendLine("   出处：${sec.source.trim()}")
            }
            appendLine()
        }
    }

    /** 成文：一次性 JSON 输出。素材不足必须拒稿 */
    override suspend fun composeArticle(context: DeepeningContext): ComposeResult {
        val cfg = configProvider()

        // 素材不足在本地就拦住，不发给模型。
        //
        // 以前只在 prompt 里要求「少于 300 字就返回 rejected」，
        // 真机实测：模型确实拒绝了，但它把拒绝的理由
        // 写成了 `{"title": "你聊得太少了…", "paragraphs": [...]}`——
        // 一个**正常文章的形状**。App 于是把它当成成稿渲染，
        // 还给了「存进思考库」按钮，用户能把一段拒稿说明存成文章。
        //
        // 字数我们自己数得清，这件事不该交给模型的自觉。
        val material = context.materialSize
        if (material < ThinMaterial.THRESHOLD) {
            return ComposeResult.Rejected(material, ThinMaterial.THRESHOLD)
        }

        return try {
            val body = RequestBuilder.compose(
                cfg,
                // 风格段可换，禁区不可换——结构在 Prompts.draftSystem 里保证，
                // 这里只负责把用户的两种输入（选了哪种风格、补充要求）传进去
                Prompts.draftSystem(context.style, context.styleInstruction),
                composeMessages(context),
            )
            val raw = transport.post(ResponseParser.endpoint(cfg.baseUrl), cfg.apiKey, body)
            val content = ResponseParser.extractContent(raw)
            // 服务商回了 200，但里面没有 content。
            // 这时候 raw 是唯一的线索——必须带出去，
            // 否则「没写成」三个字后面什么依据都没有。
                ?: return ComposeResult.Failed(
                    ErrorKindLite.UNKNOWN,
                    detail = "响应里没有 content 字段：${raw.take(300)}",
                )

            parseCompose(content, context.materialSize)
        } catch (e: TransportException) {
            // 把可归因的错误原样带出去，连服务商原文一起。
            // 之前所有异常都归成 NETWORK——用户 key 填错时会看到
            // 「网络不可用」，然后去查 WiFi，方向完全错了
            ComposeResult.Failed(e.kind.toLite(), detail = e.detail)
        } catch (e: Exception) {
            ComposeResult.Failed(ErrorKindLite.NETWORK, detail = e.message)
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

    /** 生成完整骨架 + 手边的料：意群短语，不是逐字稿 */
    override suspend fun buildOutline(
        article: ArticleDraft,
        angle: Angle,
        sprouts: List<Sprout>,
    ): ScriptOutline {
        val cfg = configProvider()
        return try {
            val body = RequestBuilder.outline(
                cfg,
                Prompts.OUTLINE_SYSTEM_PROMPT,
                angle.title,
                // 必须把文章一起发过去：骨架是从文章里拆出来的，
                // 只给一个角度名，模型没有任何可拆的东西
                buildArticleText(article),
                // 已有的芽一起发：素材优先复用它，不够才新找
                sprouts,
            )
            val raw = transport.post(ResponseParser.endpoint(cfg.baseUrl), cfg.apiKey, body)
            val content = ResponseParser.extractContent(raw)
                ?: return ScriptOutline(angle.title, emptyList())
            parseOutline(content, angle.title)
        } catch (e: Exception) {
            ScriptOutline(angle.title, emptyList())
        }
    }

    /**
     * 发芽：从记录里长出新的连接。
     *
     * 和成文一样走一次性 JSON 请求，但**不做本地素材预判**——
     * 一句「我怕他们觉得我水」只有九个字，成不了文章，
     * 却完全值得发芽。这里是全 App 唯一不需要字数门槛的 AI 能力：
     * 越短的一句话，往往越有得连接。
     */
    override suspend fun sprout(context: SproutContext): SproutResult {
        val cfg = configProvider()
        return try {
            val body = RequestBuilder.sprout(
                cfg,
                Prompts.sproutSystem(context.previousTitles),
                sproutInput(context),
            )
            val raw = transport.post(ResponseParser.endpoint(cfg.baseUrl), cfg.apiKey, body)
            val content = ResponseParser.extractContent(raw)
                ?: return SproutResult.Failed("响应里没有 content：${raw.take(300)}")
            parseSprout(content)
        } catch (e: TransportException) {
            SproutResult.Failed(e.detail ?: ErrorMapper.humanize(e.kind, e.detail))
        } catch (e: Exception) {
            SproutResult.Failed(e.message)
        }
    }

    /** 发芽的输入：原始记录 + 追问里说过的句子 */
    private fun sproutInput(c: SproutContext): String = buildString {
        appendLine("我记下的一条：")
        if (c.noteTitle.isNotBlank()) appendLine("标题：${c.noteTitle.trim()}")
        appendLine(c.noteBody.trim())
        val said = c.answers.filter { it.isNotBlank() }
        if (said.isNotEmpty()) {
            appendLine()
            appendLine("后来我又被追问了几轮，是这样回答的：")
            said.forEach { appendLine("- ${it.trim()}") }
        }
        appendLine()
        appendLine("请按上面的要求，长出 3 节。")
    }

    private fun parseSprout(content: String): SproutResult {
        val trimmed = stripCodeFence(content)
        val title = extractField(trimmed, "title")?.takeIf { it.isNotBlank() }
            ?: "长出来的东西"

        val sectionsRaw = extractArrayField(trimmed, "sections")
            ?: return SproutResult.Failed(
                "没有 sections 字段，模型返回：${trimmed.take(300)}",
            )

        val sections = splitObjects(sectionsRaw).mapNotNull { obj ->
            val insight = extractField(obj, "insight")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SproutSection(
                seed = extractField(obj, "seed") ?: "",
                insight = insight,
                source = extractField(obj, "source") ?: "",
            )
        }
        if (sections.isEmpty()) {
            return SproutResult.Failed("sections 里没有可取出的内容：${sectionsRaw.take(300)}")
        }
        return SproutResult.Success(title, sections)
    }

    /** 连接测试：设置页「测试连接」按钮用 */
    override suspend fun testConnection(config: ProviderConfig): ConnectionResult {        val start = System.currentTimeMillis()
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
     *
     * ## 顺便记账（[audit]）
     *
     * 「没解析出内容」的帧不再直接丢掉，而是记下它到底是
     * 思考帧还是错误帧。空回复因此能说清原因。
     */
    private fun Flow<String>.parseContent(audit: StreamAudit): Flow<String> = flow {
        collect { chunk ->
            // 兼容一批里塞了多行的情况
            val lines = if (chunk.contains('\n')) chunk.split('\n') else listOf(chunk)
            for (line in lines) {
                if (line.isBlank()) continue
                audit.frames++
                val content = SseParser.parse(line)
                if (content != null) {
                    audit.chars += content.length
                    emit(content)
                    continue
                }
                if (audit.errorText == null) audit.errorText = SseParser.extractError(line)
                if (SseParser.hasReasoning(line)) audit.reasoningFrames++
            }
        }
    }.catch { e ->
        if (e is TransportException) throw e
        throw TransportException(ErrorKind.MALFORMED_STREAM, e.message, detail = audit.errorText)
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
            ?: return ComposeResult.Failed(
                ErrorKindLite.UNKNOWN,
                // 解析失败时，模型到底返回了什么才是关键线索
                detail = "没有 paragraphs 字段，模型返回：${trimmed.take(300)}",
            )

        val paragraphs = splitObjects(parasRaw).mapNotNull { obj ->
            val text = extractField(obj, "text") ?: return@mapNotNull null
            val mine = extractField(obj, "isMine")?.trim() == "true"
            val fromSprout = extractField(obj, "fromSprout")?.trim() == "true"
            DraftParagraph(
                text = text,
                // 用了芽里的东西就一定是别人说的。
                // 模型偶尔会两个都标 true（它把「我引用了别人」和
                // 「这是我的观点」混成一句），这里强制纠正——
                // 「别人说的话被标成你说的」是来源视图彻底失效的那种错
                isMine = mine && !fromSprout,
                fromSprout = fromSprout,
            )
        }
        if (paragraphs.isEmpty()) {
            return ComposeResult.Failed(
                ErrorKindLite.UNKNOWN,
                detail = "paragraphs 里没有可取出的段落：${parasRaw.take(300)}",
            )
        }
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

        // 📌 素材必须过一遍准入规则，**不靠模型自觉**。
        // 它太容易给出「某项研究显示 67% 的人……」这种听着很真的东西，
        // 而用户会原样讲出去。没有出处的数据/金句/引用在这里就被丢掉，
        // 宁可素材区只剩两条。
        val materials = extractArrayField(trimmed, "materials")
            ?.let { raw ->
                splitObjects(raw).mapNotNull { obj ->
                    val text = extractField(obj, "text")?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    Material(
                        kind = MaterialKind.of(extractField(obj, "kind")),
                        text = text,
                        source = extractField(obj, "source").orEmpty(),
                    )
                }
            }.orEmpty()

        return ScriptOutline(
            angle = angleTitle,
            sections = sections,
            materials = MaterialKit.sanitize(materials),
        )
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
        /** 素材下限，与 [ThinMaterial.THRESHOLD] 保持一致 */
        const val THIN_MATERIAL = ThinMaterial.THRESHOLD
    }
}

/**
 * 成文素材不足时拒稿的阈值。
 *
 * ## 为什么从 300 降到 60
 *
 * 300 是「一篇公众号长文」的量，而这个 App 记的是**一句随感**。
 * 用户随手记 40 字、聊三轮答了 80 字，加起来一百二十字——
 * 完全写得出一篇三百字的短文。卡在 300 上，等于告诉他
 * 「你的想法太短了，不值得写」。
 *
 * 60 的作用只剩一个：**别让模型在几乎空白的地基上编一篇出来**。
 * 低于这个量，拼不出一个完整意思，那时候拒稿才是在保护他。
 *
 * 至于写长写短，交给 prompt（DRAFT_SYSTEM_PROMPT 的「长度跟着素材走」），
 * 不在这里设第二个门槛。
 */
object ThinMaterial {
    const val THRESHOLD = 60
}

/**
 * 一次流式请求的记账。
 *
 * 存在的唯一理由是：**让空回复说得出原因**。
 *
 * 以前 `parse` 返回 null 的帧被直接丢掉，于是三种完全不同的故障
 * 在界面上长得一模一样，都是「模型没返回问题」：
 *
 * | 实际发生了什么 | 用户该做什么 |
 * |---|---|
 * | 思考模式吃光了输出 | 去设置里关掉思考 |
 * | 流里带了 error 帧（额度/审核） | 看原文，充值或改内容 |
 * | 服务商什么都没推 | 重试或换模型 |
 *
 * 现在它们分别抛不同异常，界面给不同话术。
 */
internal class StreamAudit {
    /** 收到的 SSE 行数（含空帧与注释帧） */
    var frames = 0

    /** 解析出的正文总字数 */
    var chars = 0

    /** 含非空 reasoning_content 的帧数 */
    var reasoningFrames = 0

    /** 流里夹带的 error 原文 */
    var errorText: String? = null

    /** 流结束但一个字都没有 → 抛出可归因的异常 */
    fun raiseIfNothing() {
        if (chars > 0) return
        throw when {
            errorText != null -> TransportException(
                kind = ErrorKind.STREAM_ERROR,
                detail = errorText,
            )

            reasoningFrames > 0 -> TransportException(
                kind = ErrorKind.THINKING_ONLY,
                detail = "只收到 $reasoningFrames 段思考内容，正文为空",
            )

            frames == 0 -> TransportException(
                kind = ErrorKind.NO_CONTENT,
                detail = "连接已建立，服务商没有推送任何数据",
            )

            else -> TransportException(
                kind = ErrorKind.NO_CONTENT,
                detail = "收到 $frames 段数据，没有一段能解析出正文",
            )
        }
    }
}

/** 传输层异常，带可归因的错误类型 */
class TransportException(
    val kind: ErrorKind,
    message: String? = null,
    /** HTTP 状态码。0 表示没走到响应（断网、超时） */
    val statusCode: Int = 0,
    /** 服务商返回的错误原文。给 [ErrorMapper.humanize] 用，能让报错说得出原因 */
    val detail: String? = null,
) : Exception(message)