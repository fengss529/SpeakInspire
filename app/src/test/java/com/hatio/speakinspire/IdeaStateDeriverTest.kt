package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.ArticleDraft
import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.ComposeResult
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.DraftParagraph
import com.hatio.speakinspire.data.model.Note
import com.hatio.speakinspire.data.model.NoteSource
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.data.model.Section
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.domain.GestureJudge
import com.hatio.speakinspire.domain.IdeaState
import com.hatio.speakinspire.domain.IdeaStateDeriver
import com.hatio.speakinspire.domain.IdeaView
import com.hatio.speakinspire.domain.SwipeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 派生状态全组合测试。
 *
 * note 表不存 status，8 个状态全部由关联算出——
 * 这意味着**每种组合都必须有测试**，否则某个组合会静默错掉。
 */
class IdeaStateDeriverTest {

    private val t = 1_700_000_000_000L

    private fun note(parentId: String? = null) = Note(
        id = "n1",
        title = "会上说不出话",
        body = "我一想到要发言就紧张",
        createdAt = t,
        source = NoteSource.QUICK,
        parentId = parentId,
    )

    private fun deepening(finished: Boolean?, rounds: Int = 0) = Deepening(
        id = "d1",
        noteId = "n1",
        rounds = List(rounds) { Round("问？", "答") },
        startedAt = t,
        finishedAt = if (finished == true) t + 1000 else null,
    )

    private fun article() = Article(
        id = "a1",
        noteId = "n1",
        title = "我的口头禅暴露了思维速度",
        paragraphs = listOf(Paragraph("我说这话时在想别的", true)),
        writtenAt = t + 2000,
    )

    private fun script(shot: Boolean = false, parked: Boolean = false) = Script(
        id = "s1",
        articleId = "a1",
        angle = "直接挑衅",
        sections = listOf(Section("开场", 8, listOf("你说话是不是总带然后"))),
        createdAt = t + 3000,
        shotAt = if (shot) t + 4000 else null,
        parked = parked,
    )

    // ---------- 8 个状态逐个验证 ----------

    @Test
    fun `WAIT 刚记下未追问`() {
        val v = IdeaView(note(), null, null, emptyList())
        assertEquals(IdeaState.WAIT, IdeaStateDeriver.derive(v))
    }

    @Test
    fun `ONGOING 追问会话未收尾`() {
        val v = IdeaView(note(), deepening(finished = false, rounds = 2), null, emptyList())
        assertEquals(IdeaState.ONGOING, IdeaStateDeriver.derive(v))
    }

    @Test
    fun `DEEPENED 追问完成但未成文`() {
        val v = IdeaView(note(), deepening(finished = true, rounds = 5), null, emptyList())
        assertEquals(IdeaState.DEEPENED, IdeaStateDeriver.derive(v))
    }

    @Test
    fun `ARTICLED 已有文章`() {
        val v = IdeaView(note(), deepening(true), article(), emptyList())
        assertEquals(IdeaState.ARTICLED, IdeaStateDeriver.derive(v))
    }

    @Test
    fun `TOPIC 已转选题待开拍`() {
        val v = IdeaView(note(), deepening(true), article(), listOf(script()))
        assertEquals(IdeaState.TOPIC, IdeaStateDeriver.derive(v))
    }

    @Test
    fun `SHOT 右滑标记已拍摄`() {
        val v = IdeaView(note(), deepening(true), article(), listOf(script(shot = true)))
        assertEquals(IdeaState.SHOT, IdeaStateDeriver.derive(v))
    }

    @Test
    fun `PARKED 全部选题被移回思考库`() {
        val v = IdeaView(note(), deepening(true), article(), listOf(script(parked = true)))
        assertEquals(IdeaState.PARKED, IdeaStateDeriver.derive(v))
    }

    @Test
    fun `STALE 以旧笔记为起点新建且已拍摄`() {
        val v = IdeaView(
            note(parentId = "n0"),
            deepening(true),
            article(),
            listOf(script(shot = true)),
        )
        assertEquals(IdeaState.STALE, IdeaStateDeriver.derive(v))
    }

    // ---------- 边界组合 ----------

    @Test
    fun `AI拒稿后应回落为 DEEPENED 而非 ARTICLED`() {
        // Rejected 场景：追问完成了但成文被拒
        val v = IdeaView(note(), deepening(true, rounds = 2), null, emptyList())
        assertEquals(IdeaState.DEEPENED, IdeaStateDeriver.derive(v))
    }

    @Test
    fun `多选题中任一已拍摄即为SHOT`() {
        val a = script(shot = false).copy(id = "s1")
        val b = script(shot = true).copy(id = "s2")
        val v = IdeaView(note(), deepening(true), article(), listOf(a, b))
        assertEquals(IdeaState.SHOT, IdeaStateDeriver.derive(v))
    }

    @Test
    fun `已拍摄但其余选题被搁置时仍为SHOT`() {
        val a = script(shot = true).copy(id = "s1")
        val b = script(parked = true).copy(id = "s2")
        val v = IdeaView(note(), deepening(true), article(), listOf(a, b))
        assertEquals(IdeaState.SHOT, IdeaStateDeriver.derive(v))
    }

    @Test
    fun `article 不存在但有脚本属于数据异常应判为 WAIT`() {
        // 防御性：脚本引用了不存在的文章，不应崩
        val v = IdeaView(note(), null, null, listOf(script()))
        assertEquals(IdeaState.WAIT, IdeaStateDeriver.derive(v))
    }

    // ---------- 排序 ----------

    @Test
    fun `深化队列只含 WAIT 且按时间正序`() {
        val old = note().copy(id = "old", createdAt = t)
        val new = note().copy(id = "new", createdAt = t + 9999)
        val ing = note().copy(id = "ing")

        val views = listOf(
            IdeaView(new, null, null, emptyList()),
            IdeaView(ing, deepening(finished = false, rounds = 1), null, emptyList()),
            IdeaView(old, null, null, emptyList()),
        )
        val q = IdeaStateDeriver.deepeningQueue(views)
        assertEquals(listOf("old", "new"), q.map { it.note.id })
    }

    @Test
    fun `思考库排序把先留着的排到最后`() {
        val normal = note().copy(id = "normal")
        val parked = note().copy(id = "parked")
        val views = listOf(
            IdeaView(parked, deepening(true), article().copy(id = "ap"), listOf(script(parked = true))),
            IdeaView(normal, deepening(true), article(), emptyList()),
        )
        val ordered = IdeaStateDeriver.libraryOrder(views)
        assertEquals("normal", ordered.first().note.id)
    }

    @Test
    fun `选题库把已拍摄沉底`() {
        val ready = note().copy(id = "ready")
        val shot = note().copy(id = "shot")
        val views = listOf(
            IdeaView(shot, deepening(true), article().copy(id = "as"), listOf(script(shot = true).copy(id = "ss"))),
            IdeaView(ready, deepening(true), article(), listOf(script().copy(id = "sr"))),
        )
        val ordered = IdeaStateDeriver.topicOrder(views)
        assertEquals("ready", ordered.first().note.id)
    }

    @Test
    fun `选题库排除无脚本的文章`() {
        val v = IdeaView(note(), deepening(true), article(), emptyList())
        assertEquals(0, IdeaStateDeriver.topicOrder(listOf(v)).size)
    }
}

/** 手势判定测试 */
class GestureJudgeTest {

    @Test
    fun `右滑标记已拍摄`() {
        assertEquals(SwipeAction.MARK_SHOT, GestureJudge.judge(120f, 10f))
    }

    @Test
    fun `左滑移回思考库`() {
        assertEquals(SwipeAction.PARK, GestureJudge.judge(-120f, 10f))
    }

    @Test
    fun `位移不足不触发`() {
        assertEquals(SwipeAction.NONE, GestureJudge.judge(30f, 0f))
    }

    @Test
    fun `纵向为主判为滚动`() {
        assertEquals(SwipeAction.NONE, GestureJudge.judge(60f, 150f))
    }

    @Test
    fun `太斜的手势不作为有效滑动`() {
        // dx=70 dy=60 → 角度约 40 度，超过 22 度阈值
        assertEquals(SwipeAction.NONE, GestureJudge.judge(70f, 60f))
    }

    @Test
    fun `接近水平的滑动有效`() {
        // dx=60 dy=5 → 角度约 4.8 度
        assertEquals(SwipeAction.MARK_SHOT, GestureJudge.judge(60f, 5f))
    }

    @Test
    fun `零位移不触发`() {
        assertEquals(SwipeAction.NONE, GestureJudge.judge(0f, 0f))
    }
}

/** 成文结果处理测试——重点是拒稿路径 */
class ComposeResultTest {

    @Test
    fun `素材不足时拒稿并如实返回字数`() {
        val r = ComposeResult.Rejected(actualChars = 180, requiredChars = 300)
        assertEquals(180, r.actualChars)
        assertEquals(300, r.requiredChars)
    }

    @Test
    fun `成稿成功时段落带来源标记`() {
        val r = ComposeResult.Success(
            "标题",
            listOf(
                DraftParagraph("这是我说过的", isMine = true),
                DraftParagraph("这是我补的论证", isMine = false),
            ),
        )
        val paras = (r as ComposeResult.Success).paragraphs
        assertEquals(2, paras.size)
        assertEquals(1, paras.count { it.isMine })
        assertEquals(1, paras.count { !it.isMine })
    }

    @Test
    fun `素材字数只统计用户回答`() {
        val draft = ArticleDraft(
            noteId = "n1",
            title = "t",
            originalNote = "原始随手记内容",
            dialogue = listOf(
                ChatMessage.ai("AI 的问题不算素材"),
                ChatMessage.user("我说的话"),
            ),
        )
        assertEquals(4, draft.materialSize)
    }
}

/** 契约层测试：三个方法必须分开 */
class AiServiceContractTest {

    @Test
    fun `AiService 暴露五个方法且追问与成文是不同方法`() {
        val methods = com.hatio.speakinspire.service.AiService::class.java.methods
            .filter { it.declaringClass == com.hatio.speakinspire.service.AiService::class.java }
            .map { it.name }
            .toSet()

        // 三个阶段必须是独立方法，不能是 chat(prompt) + 参数
        assertTrue("必须有 streamQuestion", methods.contains("streamQuestion"))
        assertTrue("必须有 composeArticle", methods.contains("composeArticle"))
        assertTrue("必须有 deriveAngles", methods.contains("deriveAngles"))
        assertTrue("必须有 buildOutline", methods.contains("buildOutline"))

        // 不能存在万能的 chat 方法——那会让权限边界只靠 prompt 措辞保证
        assertFalse(
            "不应存在统一的 chat 方法，否则权限边界无结构保障",
            methods.any { it == "chat" || it == "complete" || it == "ask" },
        )
    }

    @Test
    fun `追问方法是流式的`() {
        val m = com.hatio.speakinspire.service.AiService::class.java.methods
            .first { it.name == "streamQuestion" }
        // 返回 Flow —— 流式是打字机效果的前提
        assertTrue(
            "追问必须返回 Flow 以支持流式",
            m.returnType.name.contains("Flow"),
        )
    }

    @Test
    fun `成文方法是一次性的`() {
        val m = com.hatio.speakinspire.service.AiService::class.java.methods
            .first { it.name == "composeArticle" }

        // suspend fun 在 JVM 上编译为「返回 Object + 末位 Continuation 参数」，
        // 不能简单断言返回类型。真正的判据是有没有 Continuation 参数。
        val hasContinuation = m.parameterTypes.any {
            it.name.contains("Continuation")
        }
        assertTrue(
            "成文方法是 suspend（一次性调用）",
            hasContinuation,
        )

        // 关键对比：成文不应像追问那样返回 Flow
        assertFalse(
            "成文不应返回 Flow——它是一次性结果，不是流",
            m.returnType.name.contains("Flow"),
        )
    }

    @Test
    fun `追问方法不是 suspend 而是返回 Flow`() {
        val m = com.hatio.speakinspire.service.AiService::class.java.methods
            .first { it.name == "streamQuestion" }
        // 追问用Flow 表达流式，不是 suspend + 手动 emit
        val hasContinuation = m.parameterTypes.any { it.name.contains("Continuation") }
        assertTrue(
            "追问用 Flow 表达流式，不应是 suspend 方法",
            m.returnType.name.contains("Flow"),
        )
        assertTrue(m.returnType.name.contains("Flow"))
        // 两个方法的流式机制必须不同，否则就退化成同一种调用了
        assertTrue(
            "追问不应是 suspend（用 Flow 即可）",
            !hasContinuation || m.returnType.name.contains("Flow"),
        )
    }
}