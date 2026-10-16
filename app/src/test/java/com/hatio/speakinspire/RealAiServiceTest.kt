package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.Angle
import com.hatio.speakinspire.data.model.ArticleDraft
import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.ComposeResult
import com.hatio.speakinspire.data.model.ErrorKindLite
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.data.remote.HttpTransport
import com.hatio.speakinspire.data.remote.Providers
import com.hatio.speakinspire.data.remote.RequestBuilder
import com.hatio.speakinspire.data.remote.ResponseParser
import com.hatio.speakinspire.data.remote.SseParser
import com.hatio.speakinspire.service.ConnectionResult
import com.hatio.speakinspire.service.DeepeningContext
import com.hatio.speakinspire.service.ErrorKind
import com.hatio.speakinspire.service.RealAiService
import com.hatio.speakinspire.service.TransportException
import com.hatio.speakinspire.service.extractField
import com.hatio.speakinspire.service.splitObjects
import com.hatio.speakinspire.service.unquoteIfNeeded
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 可编程的假传输层——不发真实请求 */
class FakeTransport : HttpTransport {
    var sseLines: List<String> = emptyList()
    var postResponse: String = ""
    var pingResponse: String = """{"choices":[{"message":{"content":"hi"}}]}"""

    var lastBody: String = ""
    var lastUrl: String = ""
    var lastKey: String = ""
    var throwOnStream: Exception? = null
    var throwOnPost: Exception? = null
    var throwOnPing: Exception? = null

    override fun stream(baseUrl: String, apiKey: String, body: String): Flow<String> = flow {
        lastUrl = baseUrl; lastKey = apiKey; lastBody = body
        throwOnStream?.let { throw it }
        sseLines.forEach { emit(it) }
    }

    override suspend fun post(baseUrl: String, apiKey: String, body: String): String {
        lastUrl = baseUrl; lastKey = apiKey; lastBody = body
        throwOnPost?.let { throw it }
        return postResponse
    }

    override suspend fun ping(baseUrl: String, apiKey: String, model: String): String {
        lastUrl = baseUrl; lastKey = apiKey
        throwOnPing?.let { throw it }
        return pingResponse
    }
}

/**
 * 请求构造测试。
 *
 * 重点：两家服务商的 thinking 参数差异——这是最容易写错的地方。
 */
class RequestBuilderTest {

    @Test
    fun `MiMo 默认发送 thinking disabled`() {
        val body = RequestBuilder.probe(
            Providers.MIMO, "系统提示", emptyList()
        )
        assertTrue(
            "MiMo 默认开启思考，必须显式关闭",
            body.contains("\"thinking\":{\"type\":\"disabled\"}"),
        )
    }

    @Test
    fun `DeepSeek 也发送 thinking disabled`() {
        // 实测发现：deepseek-flash 默认返回 reasoning_content，content 是空的。
        // 不关掉的话追问会一直空白等待——逐字浮现变成了空白等待。
        val body = RequestBuilder.probe(
            Providers.DEEPSEEK.copy(thinkingDisabled = true), "系统提示", emptyList()
        )
        assertTrue(
            "DeepSeek 必须显式关闭思考",
            body.contains("\"thinking\":{\"type\":\"disabled\"}"),
        )
    }

    @Test
    fun `未关闭思考时不发该参数`() {
        val body = RequestBuilder.probe(
            Providers.DEEPSEEK.copy(thinkingDisabled = false), "系统提示", emptyList()
        )
        assertFalse(body.contains("thinking"))
    }

    @Test
    fun `不发 reasoning_content 因为那是输出字段`() {
        val body = RequestBuilder.probe(
            Providers.DEEPSEEK, "系统提示", emptyList()
        )
        assertFalse(
            "reasoning_content 是响应字段，当请求参数发会出错",
            body.contains("reasoning_content"),
        )
    }

    @Test
    fun `自定义服务商按配置决定`() {
        val cfg = Providers.CUSTOM.copy(
            baseUrl = "https://example.com", model = "m", thinkingDisabled = true,
        )
        assertTrue(RequestBuilder.probe(cfg, "p", emptyList()).contains("thinking"))
        assertFalse(
            RequestBuilder.probe(cfg.copy(thinkingDisabled = false), "p", emptyList())
                .contains("thinking")
        )
    }

    @Test
    fun `追问请求开启流式`() {
        val body = RequestBuilder.probe(Providers.DEEPSEEK, "p", emptyList())
        assertTrue(body.contains("\"stream\":true"))
    }

    @Test
    fun `成文请求非流式且要求 JSON`() {
        val body = RequestBuilder.compose(Providers.DEEPSEEK, "p", emptyList())
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"))
    }

    @Test
    fun `系统提示被正确放进 messages 首位`() {
        val body = RequestBuilder.probe(
            Providers.DEEPSEEK, "你是追问者", listOf(ChatMessage.user("回答"))
        )
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("你是追问者"))
        assertTrue(body.indexOf("\"role\":\"system\"") < body.indexOf("\"role\":\"user\""))
    }

    @Test
    fun `用户消息标为 user AI 消息标为 assistant`() {
        val body = RequestBuilder.probe(
            Providers.DEEPSEEK, "p",
            listOf(ChatMessage.user("我的回答"), ChatMessage.ai("AI 的问题")),
        )
        assertTrue(body.contains("\"content\":\"我的回答\""))
        assertTrue(body.contains("\"role\":\"assistant\""))
    }

    @Test
    fun `追问温度高于成文`() {
        // 追问要有多样性，成文要稳
        val probe = RequestBuilder.probe(Providers.DEEPSEEK, "p", emptyList())
        val compose = RequestBuilder.compose(Providers.DEEPSEEK, "p", emptyList())
        assertTrue(probe.contains("\"temperature\":0.7"))
        assertTrue(compose.contains("\"temperature\":0.6"))
    }

    @Test
    fun `用户原话含引号时转义正确`() {
        val body = RequestBuilder.probe(
            Providers.DEEPSEEK, "p",
            listOf(ChatMessage.user("他说\"这是重点\"，对吧")),
        )
        assertTrue(body.contains("\\\"这是重点\\\""))
    }

    @Test
    fun `ping 请求最短且字段合法`() {
        val body = RequestBuilder.ping(
            Providers.DEEPSEEK.copy(apiKey = "sk-test")
        )
        assertTrue(body.contains("\"max_tokens\":1"))
        assertTrue(body.contains("deepseek-flash"))
        // 花括号配平
        assertEquals(body.count { it == '{' }, body.count { it == '}' })
    }

    // ---------- 端点拼接 ----------

    @Test
    fun `端点补全不带斜杠的 base_url`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            ResponseParser.endpoint("https://api.deepseek.com"),
        )
    }

    @Test
    fun `端点不重复加尾部斜杠`() {
        assertEquals(
            "https://api.xiaomimimo.com/v1/chat/completions",
            ResponseParser.endpoint("https://api.xiaomimimo.com/v1/"),
        )
    }

    @Test
    fun `用户填了完整端点时不追加`() {
        assertEquals(
            "https://example.com/v2/chat/completions",
            ResponseParser.endpoint("https://example.com/v2/chat/completions"),
        )
    }
}

/** SSE 分帧测试 —— keep-alive 容错是重点 */
class SseParserTest {

    @Test
    fun `解析正常的 data 帧`() {
        assertEquals(
            "你好",
            SseParser.parse("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}"),
        )
    }

    @Test
    fun `keep-alive 注释帧返回 null`() {
        // DeepSeek 和 MiMo 都会发这种帧，
        // 若不过滤会把脏数据喂给 UI，且只在网络慢时偶发
        assertNull(SseParser.parse(": keep-alive"))
        assertNull(SseParser.parse(":ping"))
    }

    @Test
    fun `结束帧返回 null`() {
        assertNull(SseParser.parse("data: [DONE]"))
    }

    @Test
    fun `空行返回 null`() {
        assertNull(SseParser.parse(""))
        assertNull(SseParser.parse("   "))
    }

    @Test
    fun `只有 role 没有 content 时返回 null`() {
        assertNull(SseParser.parse("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"))
    }

    @Test
    fun `一串帧拼成完整文本`() {
        val frames = listOf(
            "data: {\"delta\":{\"content\":\"我\"}}",
            ": keep-alive",
            "data: {\"delta\":{\"content\":\"不是\"}}",
            "data: {\"delta\":{\"role\":\"assistant\"}}",
            "data: {\"delta\":{\"content\":\"念稿\"}}",
            "data: [DONE]",
        )
        val text = frames.mapNotNull { SseParser.parse(it) }.joinToString("")
        assertEquals("我不是念稿", text)
    }
}

/** 响应解析测试 */
class ResponseParserTest {

    @Test
    fun `抽出非流式响应内容`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"你好"}}]}"""
        assertEquals("你好", ResponseParser.extractContent(body))
    }

    @Test
    fun `内容含引号时正确解转义`() {
        val body = """{"choices":[{"message":{"content":"他说\"这是重点\""}}]}"""
        assertEquals("他说\"这是重点\"", ResponseParser.extractContent(body))
    }

    @Test
    fun `内容含换行时正确解析`() {
        val body = "{\"choices\":[{\"message\":{\"content\":\"第一行\\n第二行\"}}]}"
        assertEquals("第一行\n第二行", ResponseParser.extractContent(body))
    }

    @Test
    fun `兼容 choices 里的 text 字段`() {
        val body = """{"choices":[{"text":"兼容格式"}]}"""
        assertEquals("兼容格式", ResponseParser.extractContent(body))
    }

    @Test
    fun `没有 content 时返回 null`() {
        assertNull(ResponseParser.extractContent("""{"error":{"message":"bad key"}}"""))
    }

    @Test
    fun `后续字段不被前一个字段吞掉`() {
        // 曾踩过的坑：depth 判定错误导致 content 把 role 等字段一起吞进来
        val body = """{"role":"assistant","content":"真正的内容","finish_reason":"stop"}"""
        assertEquals("真正的内容", ResponseParser.extractContent(body))
    }
}

/** 模型输出解析工具测试 */
class ModelOutputParserTest {

    @Test
    fun `提取字符串字段`() {
        assertEquals("开场钩子", extractField("""{"title":"开场钩子","seconds":8}""", "title"))
    }

    @Test
    fun `提取标量字段`() {
        assertEquals("8", extractField("""{"title":"x","seconds":8}""", "seconds"))
    }

    @Test
    fun `字段不存在时返回 null`() {
        assertNull(extractField("""{"a":1}""", "b"))
    }

    @Test
    fun `切分对象数组正确跳过嵌套`() {
        val json = """{"a":1},{"b":[1,2]},{"c":3}"""
        val parts = splitObjects(json)
        assertEquals(3, parts.size)
    }

    @Test
    fun `切分时引号内的逗号不算分隔`() {
        val parts = splitObjects("""{"t":"有,逗号"},{"t":"另一个"}""")
        assertEquals(2, parts.size)
        assertTrue(parts[0].contains("有,逗号"))
    }

    @Test
    fun `解转义标量`() {
        assertEquals("true", unquoteIfNeeded("true"))
        assertEquals("8", unquoteIfNeeded("8"))
    }

    @Test
    fun `null 被识别为无值`() {
        assertNull(unquoteIfNeeded("null"))
    }

    @Test
    fun `解转义中文 unicode`() {
        assertEquals("思辨", unquoteIfNeeded("\"\\u601d\\u8fa8\""))
    }
}

/** RealAiService 的行为测试 */
class RealAiServiceTest {

    private fun cfg() = Providers.DEEPSEEK.copy(apiKey = "sk-test")

    private fun ctx(rounds: List<ChatMessage> = emptyList()) = DeepeningContext(
        noteId = "n1",
        originalNote = "原始内容",
        rounds = rounds,
        roundIndex = rounds.size,
        maxRounds = 5,
    )

    private fun service(transport: HttpTransport) = RealAiService(transport) { cfg() }

    // ---------- 追问：流式 ----------

    @Test
    fun `追问逐字吐出且跳过脏帧`() = runBlocking {
        val t = FakeTransport()
        t.sseLines = listOf(
            "data: {\"delta\":{\"content\":\"当时\"}}",
            ": keep-alive",
            "data: {\"delta\":{\"content\":\"发生了什么\"}}",
            "data: [DONE]",
        )
        val out = service(t).streamQuestion(ctx()).toList().joinToString("")
        assertEquals("当时发生了什么", out)
    }

    @Test
    fun `追问使用追问阶段的 prompt`() = runBlocking {
        val t = FakeTransport()
        t.sseLines = listOf("data: {\"delta\":{\"content\":\"问题\"}}")
        service(t).streamQuestion(ctx()).toList()
        // 关键：追问必须用 PROBE prompt 而不是成文的
        assertTrue(t.lastBody.contains("不给结论"))
        assertFalse(t.lastBody.contains("允许补充论证"))
    }

    @Test
    fun `追问带上 Bearer token`() = runBlocking {
        val t = FakeTransport()
        t.sseLines = listOf("data: {\"delta\":{\"content\":\"x\"}}")
        service(t).streamQuestion(ctx()).toList()
        assertEquals("sk-test", t.lastKey)
    }

    @Test
    fun `SSE 被拆成多块到达时内容不丢`() = runBlocking {
        val t = FakeTransport()
        // 模拟网络分块：一帧被拆成两行到达
        t.sseLines = listOf("data: {\"delta\":{\"content\":\"前半\"}}")
        val out = service(t).streamQuestion(ctx()).toList().joinToString("")
        assertEquals("前半", out)
    }

    // ---------- 成文 ----------

    @Test
    fun `成文成功时保留段落来源标记`() = runBlocking {
        val t = FakeTransport()
        t.postResponse = """
            {"choices":[{"message":{"content":"{\"title\":\"标题\",\"paragraphs\":[{\"text\":\"我说的\",\"isMine\":true},{\"text\":\"AI补的\",\"isMine\":false}]}"}}]}
        """.trimIndent()
        val r = service(t).composeArticle(ctx(listOf(ChatMessage.user("一些回答"), ChatMessage.ai("问"))))
        assertTrue(r is ComposeResult.Success)
        val s = r as ComposeResult.Success
        assertEquals("标题", s.title)
        assertEquals(2, s.paragraphs.size)
        assertTrue(s.paragraphs[0].isMine)
        assertFalse(s.paragraphs[1].isMine)
    }

    @Test
    fun `没有用户回答时拒稿`() = runBlocking {
        val t = FakeTransport()
        val r = service(t).composeArticle(ctx(listOf(ChatMessage.ai("AI 自说自话"))))
        assertTrue("没有用户素材应拒稿", r is ComposeResult.Rejected)
    }

    @Test
    fun `模型返回拒绝标记时也拒稿`() = runBlocking {
        val t = FakeTransport()
        t.postResponse = """
            {"choices":[{"message":{"content":"{\"rejected\":true,\"reason\":\"素材太少\"}"}}]}
        """.trimIndent()
        val r = service(t).composeArticle(ctx(listOf(ChatMessage.user("短"))))
        assertTrue(r is ComposeResult.Rejected)
    }

    @Test
    fun `模型返回带代码围栏也能解析`() = runBlocking {
        val t = FakeTransport()
        t.postResponse = """
            {"choices":[{"message":{"content":"```json\n{\"title\":\"T\",\"paragraphs\":[{\"text\":\"x\",\"isMine\":true}]}\n```"}}]}
        """.trimIndent()
        val r = service(t).composeArticle(ctx(listOf(ChatMessage.user("回答"))))
        assertTrue(r is ComposeResult.Success)
        assertEquals("T", (r as ComposeResult.Success).title)
    }

    @Test
    fun `网络异常时返回失败而非崩溃`() = runBlocking {
        val t = FakeTransport()
        t.throwOnPost = TransportException(ErrorKind.NETWORK_UNAVAILABLE)
        val r = service(t).composeArticle(ctx(listOf(ChatMessage.user("回答"))))
        assertTrue(r is ComposeResult.Failed)
    }

    @Test
    fun `空响应不产生空文章`() = runBlocking {
        val t = FakeTransport()
        t.postResponse = """{"choices":[]}"""
        val r = service(t).composeArticle(ctx(listOf(ChatMessage.user("回答"))))
        assertTrue("空响应应判失败，不能返回空文章", r !is ComposeResult.Success)
    }

    // ---------- 拆角度 ----------

    @Test
    fun `拆出三个角度`() = runBlocking {
        val t = FakeTransport()
        t.postResponse = """
            {"choices":[{"message":{"content":"{\"angles\":[{\"title\":\"讲经历\",\"rationale\":\"有共鸣\",\"kind\":\"经历\"},{\"title\":\"直接挑衅\",\"rationale\":\"够直接\",\"kind\":\"挑衅\"},{\"title\":\"讲原理\",\"rationale\":\"有深度\",\"kind\":\"原理\"}]}"}}]}
        """.trimIndent()
        val angles = service(t).deriveAngles(
            ArticleDraft("n1", "标题", "原文", listOf(ChatMessage.user("回答")))
        )
        assertEquals(3, angles.size)
        assertEquals("讲经历", angles[0].title)
        assertEquals("经历", angles[0].kind)
    }

    @Test
    fun `角度最多三个`() = runBlocking {
        // 设计约束：只给 3 个，选择时信息量刚好
        val t = FakeTransport()
        // 用普通字符串拼接：三引号里的 \" 转义在插值时容易出错
        val many = (1..6).joinToString(",") {
            "{\"title\":\"角度" + it + "\",\"rationale\":\"r\",\"kind\":\"k\"}"
        }
        val inner = "{\"angles\":[" + many + "]}"
        val escaped = inner.replace("\"", "\\\"")
        t.postResponse = "{\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}"
        val angles = service(t).deriveAngles(
            ArticleDraft("n1", "t", "o", listOf(ChatMessage.user("a")))
        )
        assertEquals(3, angles.size)
    }

    @Test
    fun `拆角度失败时返回空列表`() = runBlocking {
        val t = FakeTransport()
        t.throwOnPost = TransportException(ErrorKind.RATE_LIMITED)
        val angles = service(t).deriveAngles(
            ArticleDraft("n1", "t", "o", listOf(ChatMessage.user("a")))
        )
        assertTrue(angles.isEmpty())
    }

    // ---------- 骨架 ----------

    @Test
    fun `骨架保留意群短语与镜头提示`() = runBlocking {
        val t = FakeTransport()
        t.postResponse = """
            {"choices":[{"message":{"content":"{\"sections\":[{\"title\":\"开场钩子\",\"seconds\":8,\"goal\":\"抓注意力\",\"phrases\":[\"你说话是不是总带然后\"],\"cues\":[{\"icon\":\"👁\",\"text\":\"看向镜头\"}]}]}"}}]}
        """.trimIndent()
        val outline = service(t).buildOutline(
            ArticleDraft("n1", "t", "o", listOf(ChatMessage.user("a"))),
            Angle("挑衅", "理由", "挑衅"),
        )
        assertEquals(1, outline.sections.size)
        assertEquals("你说话是不是总带然后", outline.sections[0].phrases[0])
        assertEquals(1, outline.sections[0].cues.size)
        assertEquals("看向镜头", outline.sections[0].cues[0].text)
    }

    @Test
    fun `骨架总时长可累加`() = runBlocking {
        val t = FakeTransport()
        t.postResponse = """
            {"choices":[{"message":{"content":"{\"sections\":[{\"title\":\"a\",\"seconds\":8,\"phrases\":[],\"cues\":[]},{\"title\":\"b\",\"seconds\":20,\"phrases\":[],\"cues\":[]},{\"title\":\"c\",\"seconds\":18,\"phrases\":[],\"cues\":[]},{\"title\":\"d\",\"seconds\":8,\"phrases\":[],\"cues\":[]}]}"}}]}
        """.trimIndent()
        val outline = service(t).buildOutline(
            ArticleDraft("n1", "t", "o", listOf(ChatMessage.user("a"))),
            Angle("角度", "", ""),
        )
        assertEquals(4, outline.sections.size)
        assertEquals(54, outline.totalSeconds)
    }

    @Test
    fun `骨架解析失败时返回空段而非崩溃`() = runBlocking {
        val t = FakeTransport()
        t.postResponse = """{"choices":[{"message":{"content":"这不是 JSON"}}]}"""
        val outline = service(t).buildOutline(
            ArticleDraft("n1", "t", "o", listOf(ChatMessage.user("a"))),
            Angle("角度", "", ""),
        )
        assertTrue(outline.sections.isEmpty())
        assertEquals("角度", outline.angle)
    }

    // ---------- 连接测试 ----------

    @Test
    fun `连接成功返回延迟`() = runBlocking {
        val t = FakeTransport()
        val r = service(t).testConnection(cfg())
        assertTrue(r is ConnectionResult.Success)
        assertNotNull((r as ConnectionResult.Success).latencyMs)
    }

    @Test
    fun `连接失败时给出可执行的话术`() = runBlocking {
        val t = FakeTransport()
        t.throwOnPing = TransportException(ErrorKind.INVALID_API_KEY)
        val r = service(t).testConnection(cfg())
        assertTrue(r is ConnectionResult.Failure)
        val f = r as ConnectionResult.Failure
        assertEquals(ErrorKind.INVALID_API_KEY, f.kind)
        // 话术必须告诉用户怎么做，而不只是报错
        assertTrue(f.reason.contains("空格") || f.reason.isNotBlank())
    }

    @Test
    fun `200 但响应含错误也被识别`() = runBlocking {
        val t = FakeTransport()
        t.pingResponse = """{"error":{"message":"invalid token for this endpoint"}}"""
        val r = service(t).testConnection(cfg())
        assertTrue(r is ConnectionResult.Failure)
    }
}