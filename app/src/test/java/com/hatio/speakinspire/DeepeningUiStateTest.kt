package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.ui.deepening.DeepeningUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 追问页状态规则测试。
 *
 * 这三条规则是产品骨架，不是实现细节：
 * 1. 进度点用户发送后才点亮
 * 2. 折叠摘要显示用户原话，不是 AI 总结
 * 3. 收尾卡不含 AI 总结
 *
 * 靠 code review 守不住——所以写成断言。
 */
class DeepeningUiStateTest {

    private fun state(
        rounds: List<Round> = emptyList(),
        streaming: String = "",
        answeredCount: Int = 0,
        maxRounds: Int = 5,
        finished: Boolean = false,
    ) = DeepeningUiState(
        noteId = "n1",
        noteTitle = "标题",
        noteBody = "原始内容",
        rounds = rounds,
        streaming = streaming,
        answeredCount = answeredCount,
        maxRounds = maxRounds,
        finished = finished,
    )

    // ---------- 规则1：进度点 ----------

    @Test
    fun `进度点数等于已点亮的数量`() {
        assertEquals(0, state().answeredCount)
        assertEquals(2, state(answeredCount = 2).answeredCount)
    }

    @Test
    fun `进度点总数来自预估轮数`() {
        assertEquals(5, state().maxRounds)
        assertEquals(3, state(maxRounds = 3).maxRounds)
    }

    @Test
    fun `未作答时点亮数不应超过总轮数`() {
        // 逻辑约束：不能出现 6/5 这种情况
        val s = state(answeredCount = 5, maxRounds = 5)
        assertTrue(s.answeredCount <= s.maxRounds)
    }

    // ---------- 规则2：折叠摘要显示用户原话 ----------

    @Test
    fun `折叠摘要取用户原话`() {
        val s = state(rounds = listOf(Round("AI 问的问题？", "我自己说过的内容")))
        assertEquals("我自己说过的内容", s.summaryOf(s.rounds[0]))
    }

    @Test
    fun `折叠摘要绝不含 AI 的问题`() {
        val s = state(rounds = listOf(Round("这是 AI 的追问", "这是用户的回答")))
        val summary = s.summaryOf(s.rounds[0])
        assertFalse("摘要里不该出现 AI 的问题", summary.contains("AI 的追问"))
        assertTrue(summary.contains("用户的回答"))
    }

    @Test
    fun `未作答的轮次摘要标明未作答`() {
        val s = state(rounds = listOf(Round("问题", null)))
        assertEquals("（未作答）", s.summaryOf(s.rounds[0]))
    }

    @Test
    fun `空白回答视为未作答`() {
        val s = state(rounds = listOf(Round("问题", "   ")))
        assertEquals("（未作答）", s.summaryOf(s.rounds[0]))
    }

    // ---------- 打字机状态 ----------

    @Test
    fun `有流式内容时才显示气泡`() {
        assertFalse(state(streaming = "").showTypingBubble)
        assertTrue(state(streaming = "正在说的").showTypingBubble)
    }

    @Test
    fun `已完成轮次只数答过的`() {
        val s = state(
            rounds = listOf(
                Round("问1", "答1"),
                Round("问2", null),
                Round("问3", "答3"),
            )
        )
        assertEquals("只有答过的才算完成", 2, s.completedRounds)
    }

    // ---------- 收尾 ----------

    @Test
    fun `达到上限轮次时可收尾`() {
        val rounds = (1..5).map { Round("问$it", "答$it") }
        val s = state(rounds = rounds, answeredCount = 5, maxRounds = 5)
        assertTrue("答满 5 轮应显示收尾卡", s.completedRounds >= s.maxRounds)
    }

    @Test
    fun `主动收尾后标记 finished`() {
        assertTrue(state(finished = true).finished)
    }

    // ---------- 轮次拍平（发给 AI 的顺序） ----------

    @Test
    fun `轮次按时间顺序拍平`() {
        val rounds = listOf(
            Round("第一个问题", "第一个回答"),
            Round("第二个问题", "第二个回答"),
        )
        val flat = rounds.flatMap { r ->
            buildList {
                if (r.question.isNotBlank()) add(ChatMessage.ai(r.question))
                if (!r.answer.isNullOrBlank()) add(ChatMessage.user(r.answer))
            }
        }
        assertEquals(4, flat.size)
        assertEquals("第一个问题", flat[0].content)
        assertFalse(flat[0].isUser)
        assertEquals("第一个回答", flat[1].content)
        assertTrue(flat[1].isUser)
    }

    @Test
    fun `AI 问在用户答之前`() {
        // 顺序错了模型会把答案当问题回应
        val rounds = listOf(Round("问题", "回答"))
        val flat = rounds.flatMap { r ->
            listOfNotNull(
                ChatMessage.ai(r.question).takeIf { r.question.isNotBlank() },
                r.answer?.let { ChatMessage.user(it) },
            )
        }
        assertFalse(flat[0].isUser)
        assertTrue(flat[1].isUser)
    }

    @Test
    fun `未作答的最后一轮只有 AI 的问题`() {
        val rounds = listOf(Round("还没被回答的问题", null))
        val flat = rounds.flatMap { r ->
            listOfNotNull(
                ChatMessage.ai(r.question).takeIf { r.question.isNotBlank() },
                r.answer?.let { ChatMessage.user(it) },
            )
        }
        assertEquals(1, flat.size)
        assertFalse("未作答时只有 AI 的消息", flat[0].isUser)
    }

    @Test
    fun `空问题不会产生空消息`() {
        val rounds = listOf(Round("", "只有回答"))
        val flat = rounds.flatMap { r ->
            listOfNotNull(
                ChatMessage.ai(r.question).takeIf { r.question.isNotBlank() },
                r.answer?.let { ChatMessage.user(it) },
            )
        }
        assertEquals(1, flat.size)
        assertTrue(flat[0].isUser)
    }

    @Test
    fun `五轮对话拍平后长度合理`() {
        val rounds = (1..5).map { Round("问题$it", "回答$it") }
        val flat = rounds.flatMap { r ->
            listOfNotNull(
                ChatMessage.ai(r.question).takeIf { r.question.isNotBlank() },
                r.answer?.let { ChatMessage.user(it) },
            )
        }
        assertEquals("5 组问答 = 10 条消息", 10, flat.size)
    }

    // ---------- 状态初始值 ----------

    @Test
    fun `初始状态是空的且不在忙`() {
        val s = state()
        assertTrue(s.rounds.isEmpty())
        assertFalse(s.busy)
        assertFalse(s.finished)
        assertEquals(0, s.completedRounds)
    }

    @Test
    fun `初始状态没有错误信息`() {
        assertEquals(null, state().errorMessage)
    }

    // ---------- 当前待答的问题必须能被界面拿到 ----------

    /**
     * 这一组守的是整个 App 曾经最严重的一个洞。
     *
     * 界面只渲染「已回答的轮次」和「正在流式的内容」。流一结束，
     * 问题进了 rounds 但还没答，于是**没有任何一处会渲染它**——
     * 用户看到字闪一下，然后页面空空，输入框却写着「回应它」。
     *
     * `pendingQuestion` 就是那唯一该被画出来的东西。
     */
    @Test
    fun `最后一轮未回答时它就是当前问题`() {
        val s = state(
            rounds = listOf(
                Round("第一问？", "答过了"),
                Round("第二问？", null),
            )
        )
        assertEquals("第二问？", s.pendingQuestion)
        assertTrue(s.awaitingAnswer)
    }

    @Test
    fun `全部答完时没有当前问题`() {
        val s = state(rounds = listOf(Round("问？", "答")))
        assertEquals(null, s.pendingQuestion)
        assertFalse(s.awaitingAnswer)
    }

    @Test
    fun `空会话没有当前问题`() {
        assertEquals(null, state().pendingQuestion)
    }

    @Test
    fun `空问题的残渣不该被当成当前问题`() {
        // 旧版本 bug 留下的数据：轮次在，问题是空的
        val s = state(rounds = listOf(Round("问？", "答"), Round("", null)))
        assertEquals(null, s.pendingQuestion)
        assertFalse(s.awaitingAnswer)
    }

    @Test
    fun `正在流式时当前问题仍是上一轮待答的`() {
        // 流式内容走 streaming，pendingQuestion 不该跟着变成半截的词
        val s = state(
            rounds = listOf(Round("第一问？", "答"), Round("第二问？", null)),
            streaming = "第三",
        )
        assertEquals("第二问？", s.pendingQuestion)
    }
}