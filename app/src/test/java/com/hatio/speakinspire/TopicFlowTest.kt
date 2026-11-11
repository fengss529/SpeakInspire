package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.Angle
import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.OutlineSection
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.data.model.ScriptOutline
import com.hatio.speakinspire.data.model.Section
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.service.FakeAiService
import com.hatio.speakinspire.ui.topic.TopicStep
import com.hatio.speakinspire.ui.topic.TopicViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 转选题页的**行为**测试（不是状态测试）。
 *
 * ## 为什么单独写一组
 *
 * 原来的 [TopicUiStateTest] 全是纯数据断言：`displaySections` 该取谁、
 * 「已拍摄」是不是只是标记。这些都对，但它们看不见
 * `TopicViewModel.load()` 里那句 `existing == null` ——
 * 而用户报的问题正好就出在那儿：
 *
 * 「第二次转的时候就没有选择了，直接跳到拍摄骨架」。
 *
 * 一句话的分支条件，让「一篇文章能派生出多条选题」这个产品前提
 * 只成立一次。所以这里跑真实 ViewModel + 内存 DAO。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicFlowTest {

    private val noteDao = FakeNoteDao()
    private val deepeningDao = FakeDeepeningDao()
    private val articleDao = FakeArticleDao()
    private val scriptDao = FakeScriptDao()
    private lateinit var repo: IdeaRepository
    private lateinit var ai: FakeAiService

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = IdeaRepository(noteDao, deepeningDao, articleDao, scriptDao, FakeSproutDao(), FakeTrashedDao())
        ai = FakeAiService()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun seedArticle(id: String = "a1") {
        repo.saveArticle(
            Article(
                id = id,
                title = "不开口的代价",
                paragraphs = listOf(
                    Paragraph("我一直准备，但从来没发出去过。", true, false),
                    Paragraph("准备本身会变成一种拖延。", false, false),
                ),
                writtenAt = 1L,
                edited = true,
            )
        )
    }

    private suspend fun seedTopic(id: String, angle: String, shot: Boolean = false) {
        repo.saveScript(
            Script(
                id = id,
                sourceArticleId = "a1",
                sourceTitle = "不开口的代价",
                angle = angle,
                sections = listOf(Section("开场", 8, listOf("我一直准备"))),
                createdAt = 1L,
                shotAt = if (shot) 2L else null,
            )
        )
    }

    /**
     * 零选题时**不要**先进选项页。
     *
     * 没得挑的时候多一页就是多一次点击——用户点「转成一条选题」，
     * 期待的是角度，不是一份空列表。
     */
    @Test
    fun `还没有选题就直接拆角度`() = runTest {
        seedArticle()
        val vm = TopicViewModel(repo, ai)
        vm.load("a1")
        assertEquals(TopicStep.ANGLES, vm.state.value.step)
        assertTrue(vm.state.value.existingTopics.isEmpty())
    }

    /**
     * 这条是这次反馈的正题。
     *
     * 已经转出过两条选题，再点「转成一条选题」必须让他挑：
     * 看已有的哪一条，还是再拆一个新的。之前这里直接落到旧骨架上。
     */
    @Test
    fun `已经有选题时先让他挑而不是直接跳骨架`() = runTest {
        seedArticle()
        seedTopic("s1", "从自己的经历讲")
        seedTopic("s2", "从原理讲", shot = true)

        val vm = TopicViewModel(repo, ai)
        vm.load("a1")

        val s = vm.state.value
        assertEquals("有选题时必须停在选项页", TopicStep.PICK, s.step)
        assertEquals("两条都要列出来", 2, s.existingTopics.size)
        // 骨架不能顺手就显示出来——那正是用户抱怨的现象
        assertNull("还没选，就不该有骨架", s.existingScript)
        assertFalse("还没选，页面标题不该是「拍摄骨架」", s.hasExisting)
        // 列表要能区分开：靠的是切入角度
        assertEquals(
            listOf("从自己的经历讲", "从原理讲"),
            s.existingTopics.map { it.angle },
        )
        // 状态也得带上，否则用户看不出哪条拍过了
        assertTrue(s.existingTopics[1].isShot)
        assertFalse(s.existingTopics[0].isShot)
    }

    /** 从列表点一条 → 看它的骨架，并且返回时能退回列表 */
    @Test
    fun `从选项页打开一条已有选题`() = runTest {
        seedArticle()
        seedTopic("s1", "从经历讲")
        seedTopic("s2", "从原理讲")

        val vm = TopicViewModel(repo, ai)
        vm.load("a1")
        vm.openExisting("s2")

        val s = vm.state.value
        assertEquals(TopicStep.OUTLINE, s.step)
        assertEquals("s2", s.existingScript?.id)
        assertTrue("记住是从列表点进来的，返回才回得去", s.cameFromPick)

        // 退回列表
        vm.back()
        assertEquals(TopicStep.PICK, vm.state.value.step)
        assertNull(vm.state.value.existingScript)
    }

    /** 选项页点「再拆一个新角度」→ 回到角度选择 */
    @Test
    fun `再拆一条要回到角度选择`() = runTest {
        seedArticle()
        seedTopic("s1", "从经历讲")

        val vm = TopicViewModel(repo, ai)
        vm.load("a1")
        assertEquals(TopicStep.PICK, vm.state.value.step)

        vm.startNew()
        val s = vm.state.value
        assertEquals(TopicStep.ANGLES, s.step)
        assertNull("新建时不能还挂着旧骨架", s.existingScript)
        assertFalse(s.cameFromPick)
    }

    /**
     * 存完之后，返回键不该把他带回角度列表。
     *
     * 已经存好了再让他选一次角度，他会以为刚才那一下没生效。
     * 这里守的是「保存时把角度草稿清掉」这个动作——
     * 返回键靠它区分「刚选完角度」和「已经存好了」。
     */
    @Test
    fun `存好之后角度草稿要清掉`() = runTest {
        seedArticle()
        val vm = TopicViewModel(repo, ai)
        vm.load("a1")

        // 骨架生成走 FakeAiService，先给它一份能出段的返回
        ai.outline = ScriptOutline(
            angle = "从经历讲",
            sections = listOf(
                OutlineSection(
                    title = "开场",
                    seconds = 8,
                    goal = "先让人知道你在讲什么",
                    phrases = listOf("我一直准备"),
                )
            ),
        )
        vm.chooseAngle(Angle("从经历讲", "你自己的事最实", "经历"))
        vm.saveScript()

        val s = vm.state.value
        assertTrue("应该存下去了", s.saved)
        assertTrue(s.hasExisting)
        assertNull("角度草稿必须清掉，否则返回会退回角度列表", s.chosenAngle)
        assertEquals(TopicStep.OUTLINE, s.step)
    }
}
