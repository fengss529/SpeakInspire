package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.data.remote.RequestBuilder
import com.hatio.speakinspire.data.remote.Providers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求体必须是**合法 JSON**。
 *
 * ## 为什么单独一个测试文件
 *
 * 之前在 `RealAiServiceTest` 里断言请求体，用的都是 `body.contains("...")`。
 * `contains` 对语法错误完全免疫——一个缺逗号的 JSON 照样能通过所有断言，
 * 然后真机上稳定拿到 400，界面显示「出了点问题，具体原因看不出来」。
 *
 * 这不是补一条断言能解决的事，是**测试角度**的问题：
 * 要么真的把 body 解析一遍，要么等于没测。
 *
 * 所以这里自带一个极简 JSON 解析器（不引依赖，避免离线构建失败），
 * 每个 builder 的产出都必须能被它完整解析。
 */
class ProbeRequestJsonTest {

    private val cfg = ProviderConfig(
        providerId = ProviderConfig.DEEPSEEK,
        baseUrl = "https://api.deepseek.com",
        apiKey = "sk-test",
        model = "deepseek-flash",
        thinkingDisabled = true,
    )

    // ---------- probe：追问请求 ----------

    @Test
    fun `追问_只有系统提示时是合法 JSON`() {
        val body = RequestBuilder.probe(cfg, "系统提示", emptyList())
        MiniJson.parse(body)
    }

    /** 这条是真机崩溃的现场：第一问带上了原始记录，于是 messages 有内容 */
    @Test
    fun `追问_带一条原始记录时是合法 JSON`() {
        val body = RequestBuilder.probe(
            cfg,
            "系统提示",
            listOf(ChatMessage.user("我随手记下的想法：\n人生无常，接纳一切")),
        )
        MiniJson.parse(body)
    }

    @Test
    fun `追问_多轮问答时是合法 JSON`() {
        val body = RequestBuilder.probe(
            cfg,
            "系统提示",
            listOf(
                ChatMessage.user("原始记录"),
                ChatMessage.ai("你说的「接纳」具体指什么？"),
                ChatMessage.user("就是不跟自己较劲"),
                ChatMessage.ai("那你怎么判断自己是在较劲？"),
                ChatMessage.user("会反复想同一件事"),
            ),
        )
        val root = MiniJson.parse(body) as Map<*, *>
        val messages = root["messages"] as List<*>
        assertEquals("system + 5 条对话（原始记录+两问两答）= 6 条", 6, messages.size)

        // [0] 是 system；[1] 起才是对话本身
        assertEquals("system", (messages[0] as Map<*, *>)["role"])

        val opening = messages[1] as Map<*, *>
        assertEquals("user", opening["role"])
        assertEquals("原始记录", opening["content"])

        val firstQuestion = messages[2] as Map<*, *>
        assertEquals("assistant", firstQuestion["role"])
    }

    @Test
    fun `追问_原话里有引号和换行也合法`() {
        val nasty = "他说“你这不对”，我说：\n\"凭什么\"\t还有\\反斜杠"
        val body = RequestBuilder.probe(cfg, "系统提示", listOf(ChatMessage.user(nasty)))
        val root = MiniJson.parse(body) as Map<*, *>
        val messages = root["messages"] as List<*>
        assertEquals(nasty, (messages[1] as Map<*, *>)["content"])
    }

    // ---------- 其余三个 builder 一起守住 ----------

    @Test
    fun `成文请求是合法 JSON`() {
        val body = RequestBuilder.compose(
            cfg,
            "成文提示",
            listOf(ChatMessage.user("素材一"), ChatMessage.ai("追问"), ChatMessage.user("素材二")),
        )
        val root = MiniJson.parse(body) as Map<*, *>
        assertEquals("system + 3 条素材", 4, (root["messages"] as List<*>).size)
    }

    @Test
    fun `拆角度请求是合法 JSON`() {
        MiniJson.parse(RequestBuilder.angles(cfg, "角度提示", "标题：人生无常"))
    }

    @Test
    fun `骨架请求是合法 JSON`() {
        MiniJson.parse(RequestBuilder.outline(cfg, "骨架提示", "从一个具体的人讲起"))
    }

    @Test
    fun `连接测试请求是合法 JSON`() {
        MiniJson.parse(RequestBuilder.ping(cfg))
    }

    @Test
    fun `思考参数只出现一次且不影响语法`() {
        val body = RequestBuilder.probe(cfg, "系统提示", listOf(ChatMessage.user("记一句")))
        MiniJson.parse(body)
        assertEquals(
            "thinking 参数重复会把 JSON 弄坏",
            1,
            Regex("\"thinking\"").findAll(body).count(),
        )
    }

    @Test
    fun `不关思考时请求同样合法`() {
        val noThinking = cfg.copy(thinkingDisabled = false)
        val body = RequestBuilder.probe(noThinking, "系统提示", listOf(ChatMessage.user("记一句")))
        MiniJson.parse(body)
        assertTrue("没勾关思考就不该出现 thinking 参数", !body.contains("\"thinking\""))
    }

    @Test
    fun `模型名与流式开关都在`() {
        val root = MiniJson.parse(
            RequestBuilder.probe(Providers.DEEPSEEK, "系统提示", listOf(ChatMessage.user("x")))
        ) as Map<*, *>
        assertEquals("deepseek-flash", root["model"])
        assertEquals(true, root["stream"])
    }
}

/**
 * 极简 JSON 解析器——**只用于测试**。
 *
 * 为什么手写：`org.json` 在 Android 单元测试里是 stub（抛 "not mocked"），
 * 引真正的 JSON 库又要多一个测试依赖，离线环境可能装不上。
 * 这里只需要「语法是否合法」，一百来行足够，且出错位置能说清。
 */
object MiniJson {

    fun parse(text: String): Any? {
        val p = P(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.eof()) throw AssertionError("JSON 尾部有多余内容，位置 ${p.i}：${p.context()}")
        return v
    }

    private class P(val s: String) {
        var i = 0

        fun eof() = i >= s.length
        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun context(): String {
            val from = (i - 25).coerceAtLeast(0)
            val to = (i + 25).coerceAtMost(s.length)
            return "…" + s.substring(from, to) + "…"
        }

        fun value(): Any? {
            if (eof()) fail("意外结束")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    if (c == '-' || c.isDigit()) num() else fail("非法字符 '$c'")
                }
            }
        }

        fun obj(): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                expect(':')
                skipWs()
                m[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return m }
                    else -> fail("对象里期望 ',' 或 '}'")
                }
            }
        }

        fun arr(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                skipWs()
                list.add(value())
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return list }
                    else -> fail("数组里期望 ',' 或 ']'")
                }
            }
        }

        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (eof()) fail("字符串没闭合")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (eof()) fail("转义符后面什么都没有")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("\\u 后面不足 4 位")
                                val hex = s.substring(i, i + 4)
                                sb.append(
                                    hex.toIntOrNull(16)?.toChar()
                                        ?: fail("\\u$hex 不是合法的十六进制")
                                )
                                i += 4
                            }
                            else -> fail("非法转义 \\$e")
                        }
                    }
                    c < ' ' -> fail("字符串里出现未转义的控制字符 (0x${c.code.toString(16)})")
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (peek() == '-') i++
            while (!eof() && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val raw = s.substring(start, i)
            return raw.toDoubleOrNull() ?: fail("非法数字 '$raw'")
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("期望 $word")
            i += word.length
            return v
        }

        fun peek(): Char = if (eof()) '\u0000' else s[i]

        fun expect(c: Char) {
            if (eof() || s[i] != c) fail("期望 '$c' 但看到 '${if (eof()) "EOF" else s[i].toString()}'")
            i++
        }

        fun fail(msg: String): Nothing =
            throw AssertionError("$msg（位置 $i）\n上下文：${context()}")
    }
}
