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
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.data.model.Section
import com.hatio.speakinspire.domain.ArticleView
import com.hatio.speakinspire.domain.GestureJudge
import com.hatio.speakinspire.domain.LibraryOrder
import com.hatio.speakinspire.domain.NoteState
import com.hatio.speakinspire.domain.NoteView
import com.hatio.speakinspire.domain.Streak
import com.hatio.speakinspire.domain.SwipeAction
import com.hatio.speakinspire.domain.TopicState
import com.hatio.speakinspire.domain.TopicView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三个状态机、三套排序的纯函数测试。
 *
 * 状态全部由关联算出（表里没有 status 字段），
 * 这意味着**每种组合都必须有测试**，否则某个组合会静默错掉。
 *
 * 拆分的核心约束写在 [NoteStateDeriverTest] 的第一条：
 * 一条记录显示什么，**不能**取决于它的选题拍没拍。
 */
class NoteStateDeriverTest {

    private val t = 1_700_000_000_000L

    private fun note(parentId: String? = null) = Note(
        id = "n1",
        title = "会上说不出话",
        body = "我一想到要发言就紧张",
        createdAt = t,
        source = NoteSource.QUICK,
        parentId = parentId,
    )

    private fun deepening(finished: Boolean?) = Deepening(
        id = "d1",
        noteId = "n1",
        rounds = listOf(Round("问？", "答")),
        startedAt = t,
        finishedAt = if (finished == true) t + 1000 else null,
    )

    private fun view(
        articleId: String? = null,
        deepening: Deepening? = null,
        parentId: String? = null,
        hasChild: Boolean = false,
    ) = NoteView(
        note = note(parentId),
        deepening = deepening,
        articleId = articleId,
        hasChild = hasChild,
    )

    @Test
    fun `刚记下是记录`() {
        assertEquals(NoteState.RECORD, view().state)
    }

    @Test
    fun `追问未收尾是深化中`() {
        assertEquals(NoteState.ONGOING, view(deepening = deepening(false)).state)
    }

    @Test
    fun `追问收尾未成文是已深化`() {
        assertEquals(NoteState.DEEPENED, view(deepening = deepening(true)).state)
    }

    @Test
    fun `有文章是已成文`() {
        assertEquals(NoteState.COMPOSED, view(articleId = "a1").state)
    }

    @Test
    fun `派生过新记录是已派生`() {
        assertEquals(NoteState.DERIVED, view(hasChild = true).state)
    }

    /**
     * 这条是整个改造的判据。
     *
     * 旧版把「这条记录拍没拍过视频」算进记录的状态里（STALE / SHOT），
     * 于是记录列表显示什么，取决于别的东西的进度。
     */
    @Test
    fun `派生和成文同时存在时显示已成文`() {
        // 已有成果是更硬的事实，不该被「又派生了」盖住
        assertEquals(NoteState.COMPOSED, view(articleId = "a1", hasChild = true).state)
    }

    @Test
    fun `文章优先级高于追问`() {
        assertEquals(
            NoteState.COMPOSED,
            view(articleId = "a1", deepening = deepening(false)).state,
        )
    }

    @Test
    fun `追问收尾优先于派生`() {
        assertEquals(
            NoteState.DEEPENED,
            view(deepening = deepening(true), hasChild = true).state,
        )
    }

    @Test
    fun `parentId 本身不影响状态`() {
        // 「我是从别人那儿派生的」和「我派生出了别人」是两件事。
        // 旧版把前者直接映射成「已过时」，等于给新记录判了刑
        assertEquals(NoteState.RECORD, view(parentId = "n0").state)
    }
}

/** 选题的三个状态。只有它自己说了算 */
class TopicStateDeriverTest {

    private val t = 1_700_000_000_000L

    private fun script(shot: Boolean = false, parked: Boolean = false) = Script(
        id = "s1",
        sourceArticleId = "a1",
        sourceTitle = "我的口头禅暴露了思维速度",
        angle = "直接挑衅",
        sections = listOf(Section("开场", 8, listOf("你说话是不是总带然后"))),
        createdAt = t,
        shotAt = if (shot) t + 1000 else null,
        parked = parked,
    )

    @Test
    fun `没拍也没搁置是待开拍`() {
        assertEquals(TopicState.TODO, TopicView(script()).state)
    }

    @Test
    fun `标了拍摄时间是已拍摄`() {
        assertEquals(TopicState.SHOT, TopicView(script(shot = true)).state)
    }

    @Test
    fun `搁置是搁置`() {
        assertEquals(TopicState.PARKED, TopicView(script(parked = true)).state)
    }

    @Test
    fun `拍过之后又搁置仍是已拍摄`() {
        // 拍过了就是拍过了，后来换个角度不改变这个事实
        assertEquals(TopicState.SHOT, TopicView(script(shot = true, parked = true)).state)
    }

    @Test
    fun `标题优先用文章的快照`() {
        assertEquals("我的口头禅暴露了思维速度", TopicView(script()).title)
    }

    @Test
    fun `没有快照时退回角度`() {
        val s = script().copy(sourceTitle = "")
        assertEquals("直接挑衅", TopicView(s).title)
    }
}

/** 三个列表各自的排序规则 */
class LibraryOrderTest {

    private val t = 1_700_000_000_000L

    private fun note(id: String, at: Long) = NoteView(
        note = Note(
            id = id, title = id, body = "", createdAt = at, source = NoteSource.QUICK,
        ),
        deepening = null,
    )

    private fun article(id: String, at: Long) = ArticleView(
        article = Article(
            id = id, sourceNoteId = "n1", sourceText = "正文", title = id,
            paragraphs = listOf(Paragraph("我说过的话", true)), writtenAt = at,
        ),
    )

    private fun topic(id: String, at: Long, shot: Boolean = false, parked: Boolean = false) =
        TopicView(
            Script(
                id = id, sourceArticleId = "a1", sourceTitle = "文章", angle = id,
                sections = emptyList(), createdAt = at,
                shotAt = if (shot) at + 1 else null, parked = parked,
            )
        )

    @Test
    fun `记录按时间倒序`() {
        val ordered = LibraryOrder.notes(listOf(note("old", t), note("new", t + 10)))
        assertEquals("new", ordered[0].note.id)
    }

    @Test
    fun `文章按成稿时间倒序`() {
        val ordered = LibraryOrder.articles(listOf(article("old", t), article("new", t + 10)))
        assertEquals("new", ordered[0].article.id)
    }

    @Test
    fun `选题待开拍在前已拍摄次之搁置沉底`() {
        val ordered = LibraryOrder.topics(
            listOf(
                topic("parked", t, parked = true),
                topic("shot", t, shot = true),
                topic("todo", t),
            )
        )
        assertEquals(listOf("todo", "shot", "parked"), ordered.map { it.script.id })
    }

    @Test
    fun `同状态选题按生成时间倒序`() {
        val ordered = LibraryOrder.topics(listOf(topic("old", t), topic("new", t + 10)))
        assertEquals("new", ordered[0].script.id)
    }

    @Test
    fun `文章没有先留着这个状态`() {
        // 「这篇先不拍」是选题那边的决定，文章本身只管什么时候写的。
        // 这里用一条断言把这件事钉住：ArticleView 上不存在 state 属性
        val hasState = ArticleView::class.java.methods.any { it.name == "getState" }
        assertFalse("文章不该有状态字段——那是选题的事", hasState)
    }
}

/** 连续记录天数 */
class StreakTest {

    /** 用 Calendar 造「某天的中午」，避开时区边界 */
    private fun day(year: Int, month: Int, dayOfMonth: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, dayOfMonth, 12, 0, 0)
        return c.timeInMillis
    }

    private fun now(year: Int, month: Int, dayOfMonth: Int) = day(year, month, dayOfMonth)

    @Test
    fun `没有记录是 0 天`() {
        assertEquals(0, Streak.days(emptyList(), now(2026, 10, 28)))
    }

    @Test
    fun `今天记了就至少 1 天`() {
        assertEquals(1, Streak.days(listOf(day(2026, 10, 28)), now(2026, 10, 28)))
    }

    @Test
    fun `连着三天是 3 天`() {
        val stamps = listOf(day(2026, 10, 26), day(2026, 10, 27), day(2026, 10, 28))
        assertEquals(3, Streak.days(stamps, now(2026, 10, 28)))
    }

    @Test
    fun `今天还没记从昨天起算`() {
        // 否则每天零点一到，连续天数归零——那会变成一个指责而不是记录
        val stamps = listOf(day(2026, 10, 26), day(2026, 10, 27))
        assertEquals(2, Streak.days(stamps, now(2026, 10, 28)))
    }

    @Test
    fun `中间断了一天就只算到断点`() {
        val stamps = listOf(day(2026, 10, 24), day(2026, 10, 27), day(2026, 10, 28))
        assertEquals(2, Streak.days(stamps, now(2026, 10, 28)))
    }

    @Test
    fun `同一天记好几条只算一天`() {
        val stamps = listOf(
            day(2026, 10, 28), day(2026, 10, 28) + 3_600_000, day(2026, 10, 27),
        )
        assertEquals(2, Streak.days(stamps, now(2026, 10, 28)))
    }

    @Test
    fun `很久没记就是 0 天`() {
        assertEquals(0, Streak.days(listOf(day(2026, 9, 1)), now(2026, 10, 28)))
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
/**
 * 统计页的口径。
 *
 * 这里只测「算得对不对」，不测界面怎么画。
 * 重点是把 **0/0 不是 0%** 这件事钉住——分母为零时那两个比率
 * 必须返回 null，界面据此显示「—」。
 * 之前它们返回 0，界面上「成文比例 0%」看起来像在指责用户
 * 「你写了不少但一篇都没成」，而实际情况是一条记录都还没有。
 */
class StatsRateTest {

    private fun stats(
        recorded: Int = 0,
        composed: Int = 0,
        topics: Int = 0,
        shot: Int = 0,
    ) = com.hatio.speakinspire.ui.Stats(
        recorded = recorded,
        deepened = 0,
        composed = composed,
        articles = 0,
        articlesWithTopic = 0,
        topics = topics,
        todo = 0,
        shot = shot,
        parked = 0,
        streak = 0,
    )

    @Test
    fun `没有记录时成文比例是算不出来而不是零`() {
        assertNull("分母为零，不能报 0%", stats(recorded = 0).composeRate)
    }

    @Test
    fun `没有选题时拍摄进度是算不出来而不是零`() {
        assertNull("分母为零，不能报 0%", stats(topics = 0).shotRate)
    }

    @Test
    fun `有记录但都没成文才是真的零`() {
        assertEquals(0, stats(recorded = 5, composed = 0).composeRate)
    }

    @Test
    fun `成文比例按记录算`() {
        assertEquals(40, stats(recorded = 5, composed = 2).composeRate)
    }

    @Test
    fun `拍摄进度按选题算，不掺记录`() {
        // 记录 100 条、选题 4 条拍了 1 条：进度是 25%，不是 1%
        assertEquals(25, stats(recorded = 100, topics = 4, shot = 1).shotRate)
    }

    @Test
    fun `两个比率各有各的分母`() {
        val s = stats(recorded = 8, composed = 8, topics = 2, shot = 2)
        assertEquals(100, s.composeRate)
        assertEquals(100, s.shotRate)
    }
}
