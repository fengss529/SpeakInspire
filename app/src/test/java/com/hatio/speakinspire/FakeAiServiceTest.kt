package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.Angle
import com.hatio.speakinspire.data.model.ArticleDraft
import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.ComposeResult
import com.hatio.speakinspire.service.ConnectionResult
import com.hatio.speakinspire.service.DeepeningContext
import com.hatio.speakinspire.data.model.DraftParagraph
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.service.FakeAiService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FakeAiService 自身的测试。
 *
 * 如果Fake 不可靠，上层所有测试都不可靠——所以先测它。
 */
class FakeAiServiceTest {

    private fun ctx(rounds: Int = 0, roundIndex: Int = 0) = DeepeningContext(
        noteId = "n1",
        originalNote = "我一想到要发言就紧张",
        // 偶数位是 AI 的问题，奇数位是用户的回答
        rounds = List(rounds) { i ->
            if (i % 2 == 0) ChatMessage.ai("第${i / 2 + 1}个问题？")
            else ChatMessage.user("我的回答 $i")
        },
        roundIndex = roundIndex,
        maxRounds = 5,
    )

    // ---------- 能力 1：脚本化返回 ----------

    @Test
    fun `按脚本返回追问问题`() = runBlocking {
        val fake = FakeAiService()
        fake.questionScript = listOf("当时具体发生了什么？", "你怎么解释私底下聊得挺好？")
        val q1 = fake.streamQuestion(ctx()).toList().joinToString("")
        val q2 = fake.streamQuestion(ctx(rounds = 1, roundIndex = 1)).toList().joinToString("")
        assertEquals("当时具体发生了什么？", q1)
        assertEquals("你怎么解释私底下聊得挺好？", q2)
    }

    @Test
    fun `脚本耗尽后返回空流`() = runBlocking {
        val fake = FakeAiService()
        fake.questionScript = listOf("只有一个问题")
        fake.streamQuestion(ctx()).toList()
        assertTrue(fake.streamQuestion(ctx(roundIndex = 1)).toList().isEmpty())
    }

    // ---------- 能力 2：流速控制 ----------

    @Test
    fun `无延迟时一次性返回完整文本`() = runBlocking {
        val fake = FakeAiService()
        fake.questionScript = listOf("完整的一句话")
        val chunks = fake.streamQuestion(ctx()).toList()
        assertEquals(1, chunks.size)
    }

    @Test
    fun `设置延迟后按字符切分`() = runBlocking {
        val fake = FakeAiService()
        fake.questionScript = listOf("abc")
        fake.charDelayMs = 1
        val chunks = fake.streamQuestion(ctx()).toList()
        assertEquals(3, chunks.size)
        assertEquals("abc", chunks.joinToString(""))
    }

    // ---------- 能力 3：失败模拟 ----------

    @Test
    fun `追问失败可被模拟`() {
        val fake = FakeAiService()
        fake.failProbe = true
        // 必须用 runBlocking 包起来收集 Flow，不能用表达式体——
        // @Test(expected=) 要求方法返回 Unit，而表达式体的返回类型是推断出来的
        var thrown = false
        runBlocking {
            try {
                fake.streamQuestion(ctx()).toList()
            } catch (e: IllegalStateException) {
                thrown = true
            }
        }
        assertTrue("应抛出模拟的失败", thrown)
    }

    @Test
    fun `成文失败可被模拟`() = runBlocking {
        val fake = FakeAiService()
        fake.failDraft = true
        val r = fake.composeArticle(ctx(rounds = 4))
        assertTrue(r is ComposeResult.Failed)
    }

    // ---------- 能力 4：素材不足 ----------

    @Test
    fun `强制素材不足时拒稿并返回真实字数`() = runBlocking {
        val fake = FakeAiService()
        fake.forceThinMaterial = true
        val r = fake.composeArticle(ctx(rounds = 2))
        assertTrue(r is ComposeResult.Rejected)
        val rejected = r as ComposeResult.Rejected
        assertEquals(FakeAiService.THIN_MATERIAL / 3, rejected.actualChars)
        assertEquals(FakeAiService.THIN_MATERIAL, rejected.requiredChars)
        assertTrue(
            "拒稿样本的字数必须真的低于门槛，否则测的是一个不存在的边界",
            rejected.actualChars < rejected.requiredChars,
        )
    }

    @Test
    fun `素材充足时正常成稿`() = runBlocking {
        val fake = FakeAiService()
        fake.draftTitle = "我的口头禅暴露了思维速度"
        fake.draftParagraphs = listOf(
            DraftParagraph("我说『然后』的时候在想下一句", isMine = true),
            DraftParagraph("垫词机制的本质是工作记忆占用", isMine = false),
        )
        val r = fake.composeArticle(ctx(rounds = 5)) as ComposeResult.Success
        assertEquals("我的口头禅暴露了思维速度", r.title)
        assertEquals(2, r.paragraphs.size)
        assertTrue(r.paragraphs.any { !it.isMine })
    }

    // ---------- 能力 5：提前收尾 ----------

    @Test
    fun `提前收尾不硬凑五轮`() = runBlocking {
        val fake = FakeAiService()
        fake.questionScript = listOf("q1", "q2", "q3", "q4", "q5")
        fake.wrapUpAfter = 2   // 用户第 2 轮就想通了
        repeat(2) { fake.streamQuestion(ctx(roundIndex = it)).toList() }
        assertTrue(
            "第 3 轮应为空流，表示 AI 主动收尾",
            fake.streamQuestion(ctx(roundIndex = 2)).toList().isEmpty(),
        )
    }

    @Test
    fun `不设提前收尾时跑满五轮`() = runBlocking {
        val fake = FakeAiService()
        fake.questionScript = listOf("q1", "q2", "q3", "q4", "q5")
        repeat(5) { fake.streamQuestion(ctx(roundIndex = it)).toList() }
        assertTrue(fake.streamQuestion(ctx(roundIndex = 5)).toList().isEmpty())
    }

    // ---------- 能力 6：请求捕获（最关键） ----------

    @Test
    fun `捕获追问请求用于断言 prompt`() = runBlocking {
        val fake = FakeAiService()
        fake.questionScript = listOf("问题")
        fake.streamQuestion(ctx()).toList()
        val c = fake.captured.first()
        assertEquals(FakeAiService.Stage.PROBE, c.stage)
        assertTrue("捕获到的 system prompt 应包含禁止给答案的措辞",
            c.systemPrompt.contains("不给结论"))
    }

    @Test
    fun `捕获的追问 prompt 不含补位式措辞`() = runBlocking {
        val fake = FakeAiService()
        fake.questionScript = listOf("问题")
        fake.streamQuestion(ctx()).toList()
        val prompt = fake.captured.first().systemPrompt

        // 「补充论证」是成文阶段才有的能力，追问 prompt 里完全不该出现——
        // 连「禁止它」的提法都不该有，因为追问 prompt 根本不该知道这个概念。
        assertFalse(
            "追问 prompt 不应出现「补充论证」——那是成文阶段的能力",
            prompt.contains("补充论证"),
        )

        // 但「我建议你」必须出现在禁止清单里，正是为了禁止它。
        // 所以这里断言的是它带禁止标记，而不是单纯的不存在。
        val idx = prompt.indexOf("我建议你")
        if (idx >= 0) {
            val before = prompt.substring(maxOf(0, idx - 20), idx)
            assertTrue(
                "「我建议你」只能作为反例出现，前面须有禁止标记，实际：…$before",
                before.contains("不说") || before.contains("禁止"),
            )
        }
    }

    @Test
    fun `三个阶段的请求分别被捕获`() = runBlocking {
        val fake = FakeAiService()
        fake.questionScript = listOf("问题")
        fake.draftTitle = "t"
        fake.angles = listOf(Angle("直接挑衅", "开口就反问", "挑衅"))
        fake.streamQuestion(ctx()).toList()
        fake.composeArticle(ctx(rounds = 4))
        fake.deriveAngles(
            ArticleDraft("n1", "t", "原", listOf(ChatMessage.user("答")))
        )
        val stages = fake.captured.map { it.stage }
        assertTrue(stages.contains(FakeAiService.Stage.PROBE))
        assertTrue(stages.contains(FakeAiService.Stage.DRAFT))
        assertTrue(stages.contains(FakeAiService.Stage.ANGLES))
    }

    @Test
    fun `上下文随请求一并捕获以供断言`() = runBlocking {
        val fake = FakeAiService()
        fake.questionScript = listOf("问题")
        val c = ctx(rounds = 2, roundIndex = 2)
        fake.streamQuestion(c).toList()
        val captured = fake.captured.first().context
        assertEquals(c, captured)
    }

    // ---------- 连接测试 ----------

    @Test
    fun `连接测试成功返回延迟与上下文窗口`() = runBlocking {
        val fake = FakeAiService()
        val r = fake.testConnection(
            ProviderConfig(ProviderConfig.DEEPSEEK, "https://api.deepseek.com", "sk-x", "deepseek-flash")
        )
        assertTrue(r is ConnectionResult.Success)
        assertEquals(1_000_000L, (r as ConnectionResult.Success).contextWindow)
    }

    @Test
    fun `连接测试可模拟可归因错误`() = runBlocking {
        val fake = FakeAiService()
        fake.failPing = ConnectionResult.Failure(
            com.hatio.speakinspire.service.ErrorKind.MODEL_NOT_FOUND,
            "模型名不存在",
        )
        val r = fake.testConnection(
            ProviderConfig(ProviderConfig.MIMO, "https://api.xiaomimimo.com/v1", "tp-x", "mimo-v2.5-pro")
        )
        assertTrue(r is ConnectionResult.Failure)
        assertEquals(
            com.hatio.speakinspire.service.ErrorKind.MODEL_NOT_FOUND,
            (r as ConnectionResult.Failure).kind,
        )
    }
}