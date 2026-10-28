package com.hatio.speakinspire

import com.hatio.speakinspire.data.local.ArticleEntity
import com.hatio.speakinspire.data.local.DeepeningEntity
import com.hatio.speakinspire.data.local.NoteEntity
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.data.repository.InMemoryProbeMethodStore
import com.hatio.speakinspire.data.model.Angle
import com.hatio.speakinspire.data.model.OutlineSection
import com.hatio.speakinspire.data.model.ScriptOutline
import com.hatio.speakinspire.service.FakeAiService
import com.hatio.speakinspire.service.ProbeMethod
import com.hatio.speakinspire.ui.deepening.DeepeningViewModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 核心链路的**端到端**测试：追问 → 成文 → 转选题。
 *
 * 之前 276 条测试几乎都在测「状态类」和「prompt 边界」，
 * ViewModel 里的状态迁移一条都没覆盖——而真机上的阻塞问题
 * （回答被丢掉、选题存了不出现）恰好全在这层。
 *
 * 这一组用 FakeAiService + 内存 DAO 跑真实调用链。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeepeningFlowTest {

    private val noteDao = FakeNoteDao()
    private val deepeningDao = FakeDeepeningDao()
    private val articleDao = FakeArticleDao()
    private val scriptDao = FakeScriptDao()
    private lateinit var repo: IdeaRepository
    private lateinit var ai: FakeAiService
    private lateinit var probeStore: InMemoryProbeMethodStore

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = IdeaRepository(noteDao, deepeningDao, articleDao, scriptDao, FakeSproutDao(), FakeTrashedDao())
        ai = FakeAiService()
        probeStore = InMemoryProbeMethodStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun seedNote(id: String = "n1") {
        noteDao.seed(
            listOf(
                NoteEntity(
                    id = id,
                    title = "上台卡住",
                    body = "我明明准备过，但张嘴就卡住",
                    createdAt = 1_000L,
                    source = "QUICK",
                    parentId = null,
                )
            )
        )
    }

    private suspend fun seedArticle(noteId: String = "n1", articleId: String = "a_n1") {
        seedNote(noteId)
        articleDao.seed(
            listOf(
                ArticleEntity(
                    id = articleId,
                    sourceNoteId = noteId,
                    sourceText = "我明明准备过，但张嘴就卡住",
                    title = "准备过，为什么还是卡住",
                    paragraphsJson = """[{"text":"我准备了整整一周","mine":true}]""",
                    writtenAt = 2_000L,
                    edited = false,
                )
            )
        )
    }

    // ---------- 追问 ----------

    @Test
    fun `进入页面自动问第一个问题`() = runTest {
        seedNote()
        ai.questionScript = listOf("当时你准备了多久？", "卡住的瞬间你脑子里在想什么？")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")

        val s = vm.state.value
        assertEquals("应该有一个问题", 1, s.rounds.size)
        assertEquals("当时你准备了多久？", s.rounds[0].question)
        assertEquals("还没回答", 0, s.answeredCount)
        assertTrue("有问题在等回答，输入框必须可用", s.awaitingAnswer)
    }

    @Test
    fun `第一问的上下文里必须带着用户原始记录`() = runTest {
        seedNote()
        ai.questionScript = listOf("当时你准备了多久？")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")

        val ctx = ai.captured.last { it.stage == FakeAiService.Stage.PROBE }.context
        assertEquals(
            "原始记录必须从笔记一路传到请求上下文",
            "我明明准备过，但张嘴就卡住",
            ctx?.originalNote,
        )
    }

    @Test
    fun `设置里选的追问方法会传进请求`() = runTest {
        seedNote()
        ai.questionScript = listOf("有人会说你就是没准备充分，你怎么回应？")
        probeStore.select(ProbeMethod.DEVILS_ADVOCATE)

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")

        assertEquals(
            "设置里选了反方质询，请求里就必须是反方质询",
            ProbeMethod.DEVILS_ADVOCATE,
            vm.state.value.probeMethod,
        )
        val ctx = ai.captured.last { it.stage == FakeAiService.Stage.PROBE }.context
        assertEquals(ProbeMethod.DEVILS_ADVOCATE, ctx?.probeMethod)
    }

    @Test
    fun `换方法后重新进入页面会读到新方法`() = runTest {
        seedNote()
        ai.questionScript = listOf("第一次的问题", "第二次的问题")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")
        assertEquals(ProbeMethod.DEFAULT, vm.state.value.probeMethod)

        // 模拟用户去设置里换了问法，再回到这个话题
        probeStore.select(ProbeMethod.STAR)
        val vm2 = DeepeningViewModel(repo, ai, probeStore)
        vm2.load("n1")

        assertEquals("重新进来应吃到新设置", ProbeMethod.STAR, vm2.state.value.probeMethod)
    }

    @Test
    fun `用户回答后历史里必须留下这句回答`() = runTest {
        seedNote()
        ai.questionScript = listOf("当时你准备了多久？", "卡住的瞬间你脑子里在想什么？")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")
        vm.send("我准备了整整一周")

        val s = vm.state.value
        assertEquals("两轮问题都应该在", 2, s.rounds.size)
        assertEquals("第一轮的问题", "当时你准备了多久？", s.rounds[0].question)
        assertEquals(
            "用户的回答不能被丢掉",
            "我准备了整整一周",
            s.rounds[0].answer,
        )
        assertEquals("第二轮是 AI 的新问题", "卡住的瞬间你脑子里在想什么？", s.rounds[1].question)
        assertTrue("第二轮还没答", s.rounds[1].answer == null)
    }

    @Test
    fun `已回答轮数按用户真正答过的算`() = runTest {
        seedNote()
        ai.questionScript = listOf("问题一", "问题二", "问题三")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")
        vm.send("回答一")
        vm.send("回答二")

        assertEquals(2, vm.state.value.answeredCount)
        assertEquals(
            "进度点必须用户发送后才亮，且不能因 AI 追问而丢",
            2,
            vm.state.value.completedRounds,
        )
    }

    @Test
    fun `问下一个问题时必须带上之前的对话`() = runTest {
        seedNote()
        ai.questionScript = listOf("问题一", "问题二")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")
        vm.send("回答一")

        val lastContext = ai.captured.last { it.stage == FakeAiService.Stage.PROBE }.context
        assertNotNull(lastContext)
        assertTrue("上下文里要有用户的回答", lastContext!!.rounds.any { it.isUser })
        assertEquals("回答一", lastContext.rounds.first { it.isUser }.content)
    }

    @Test
    fun `答满上限后能进入收尾`() = runTest {
        seedNote()
        ai.questionScript = listOf("问题一", "问题二", "问题三", "问题四", "问题五")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")
        repeat(5) { vm.send("回答${it + 1}") }

        val s = vm.state.value
        assertEquals("五轮都答过", 5, s.completedRounds)
        assertTrue("应有收尾卡", s.completedRounds >= s.maxRounds)
    }

    /**
     * 真机反馈「点收尾没反应」的根因。
     *
     * 收尾时末尾那个没回答的问题被留在 rounds 里，
     * 而收尾卡又有 `!hasPending` 的条件——两边一抵消，
     * 界面上只有一处变化：顶栏的「收尾」按钮不见了。
     *
     * 现在收尾 = 结束对话，那句话不再要了。
     */
    @Test
    fun `收尾会把末尾没回答的问题一起丢掉`() = runTest {
        seedNote()
        ai.questionScript = listOf("问题一", "问题二")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")
        vm.send("回答一")   // 问出「问题二」，待答
        vm.finishEarly()

        val s = vm.state.value
        assertTrue("用户点了收尾", s.finished)
        assertNull(
            "待答的问题要丢掉，否则收尾卡和问题会同时摆着",
            s.pendingQuestion,
        )
        assertEquals("已回答的轮次必须留住", 1, s.rounds.size)
        assertEquals("回答一", s.rounds[0].answer)
    }

    @Test
    fun `收尾后重新打开会补一个新问题`() = runTest {
        seedNote()
        ai.questionScript = listOf("问题一", "问题二", "问题三")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")
        vm.send("回答一")
        vm.finishEarly()
        vm.reopen()

        val s = vm.state.value
        assertFalse("重新打开后不该还算结束", s.finished)
        assertNotNull(
            "手里没有可答的问题就得主动问一个，否则输入框不出现",
            s.pendingQuestion,
        )
        assertTrue("必须能输入", s.awaitingAnswer)
    }

    @Test
    fun `重新进入页面不会丢历史回答`() = runTest {
        seedNote()
        ai.questionScript = listOf("问题一", "问题二", "问题三")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")
        vm.send("回答一")

        // 新建一个 VM，模拟离开页面再进来
        val vm2 = DeepeningViewModel(repo, ai, probeStore)
        vm2.load("n1")

        val s = vm2.state.value
        assertEquals("历史轮次应从库里读回", 2, s.rounds.size)
        assertEquals("回答也要读回", "回答一", s.rounds[0].answer)
        assertEquals("进度点按真实回答数点亮", 1, s.answeredCount)
        assertTrue("有未答的问题，应等待用户回答", s.awaitingAnswer)
        assertEquals("重新进入要自动聚焦", 1, s.focusTick)
    }

    @Test
    fun `网络失败时回答仍被保留`() = runTest {
        seedNote()
        ai.questionScript = listOf("问题一", "问题二")
        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")

        ai.failProbe = true
        vm.send("这句话不能丢")

        val s = vm.state.value
        assertEquals("回答还在", "这句话不能丢", s.rounds[0].answer)
        assertNotNull("应给出错误提示", s.errorMessage)
        assertTrue("失败后不该还在忙", !s.busy)
    }

    // ---------- 轮数上限与收尾卡 ----------

    private fun seedDeepening(maxRounds: Int, roundsJson: String) {
        deepeningDao.seed(
            listOf(
                DeepeningEntity(
                    id = "d_n1",
                    noteId = "n1",
                    startedAt = 1_000L,
                    finishedAt = null,
                    estimatedRounds = maxRounds,
                    roundsJson = roundsJson,
                )
            )
        )
    }

    /**
     * 真机反馈：「多了几轮后，会闪现出『写成文章』的弹窗，然后又消失，
     * 继续出现回复框。」
     *
     * 根因：答满上限的那一刻 `completedRounds >= maxRounds` 成立、
     * `pendingQuestion` 还是空 → 收尾卡出现；随后第 6 个问题流进来
     * → `pendingQuestion` 非空 → 卡片被撤掉。一进一出就是「闪一下」。
     *
     * 修法不是藏卡片，而是**让上限真的生效**：答满就收尾，不再问下一个。
     */
    @Test
    fun `答满轮数就直接收尾不再追问`() = runTest {
        seedNote()
        seedDeepening(
            maxRounds = 2,
            roundsJson = """[{"q":"问题一","a":"回答一"},{"q":"问题二","a":null}]""",
        )
        ai.questionScript = listOf("这个问题不该被问出来")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")
        vm.send("回答二")

        val s = vm.state.value
        assertEquals("两轮都答过", 2, s.completedRounds)
        assertTrue("答满上限就该收尾", s.finished)
        assertNull("不该再挂着一个待答问题", s.pendingQuestion)
        assertTrue("收尾卡必须出现", s.showFinishCard)
        assertEquals(
            "答满上限后不应该再花一次 API 调用",
            0,
            ai.captured.count { it.stage == FakeAiService.Stage.PROBE },
        )
    }

    @Test
    fun `重新进入答满上限的会话时不再自动追问`() = runTest {
        seedNote()
        seedDeepening(
            maxRounds = 2,
            roundsJson = """[{"q":"问题一","a":"回答一"},{"q":"问题二","a":"回答二"}]""",
        )

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")

        val s = vm.state.value
        assertTrue("轮数用完的会话等于结束了", s.finished)
        assertTrue("要能直接去成文", s.showFinishCard)
        assertEquals("不该自动问第 3 个问题", 0, ai.captured.size)
    }

    // ---------- 改说过的话 ----------

    /**
     * 真机反馈：「继续点『编辑』，只对最开始的记录编辑，
     * 缺乏对 AI 问题的回答的编辑。」
     */
    @Test
    fun `改过的回答会替换原话并落库`() = runTest {
        seedNote()
        ai.questionScript = listOf("问题一", "问题二")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")
        vm.send("然后就那个啥，卡住了")
        vm.updateAnswer(0, "讲到第二页时，我脑子里突然一片空白")

        assertEquals(
            "界面上的回答要立刻换掉",
            "讲到第二页时，我脑子里突然一片空白",
            vm.state.value.rounds[0].answer,
        )

        val vm2 = DeepeningViewModel(repo, ai, probeStore)
        vm2.load("n1")
        assertEquals(
            "改完必须落库，重新进来还是新的说法",
            "讲到第二页时，我脑子里突然一片空白",
            vm2.state.value.rounds[0].answer,
        )
    }

    @Test
    fun `改回答不会动别的轮次`() = runTest {
        seedNote()
        ai.questionScript = listOf("问题一", "问题二", "问题三")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")
        vm.send("回答一")
        vm.send("回答二")
        vm.updateAnswer(1, "回答二改过了")

        val s = vm.state.value
        assertEquals("第一轮不受影响", "回答一", s.rounds[0].answer)
        assertEquals("第二轮是改后的", "回答二改过了", s.rounds[1].answer)
        assertEquals("问题本身不该被改", "问题二", s.rounds[1].question)
    }

    @Test
    fun `空回答不能覆盖原话`() = runTest {
        seedNote()
        ai.questionScript = listOf("问题一", "问题二")

        val vm = DeepeningViewModel(repo, ai, probeStore)
        vm.load("n1")
        vm.send("回答一")
        vm.updateAnswer(0, "   ")

        assertEquals("清空输入框不算修改", "回答一", vm.state.value.rounds[0].answer)
    }

    // ---------- 成文 → 转选题 ----------

    @Test
    fun `转选题必须挂在真实存在的文章上`() = runTest {
        seedArticle(noteId = "n1", articleId = "a_n1")
        ai.angles = listOf(Angle("从准备说起", "你有具体细节", "story"))
        ai.outline = ScriptOutline(
            "从准备说起",
            listOf(
                OutlineSection(
                    title = "准备得越细，卡得越狠",
                    seconds = 20,
                    goal = "建立反差",
                    phrases = listOf("我准备了整整一周", "张口就卡住"),
                )
            ),
        )

        val vm = TopicViewModel(repo, ai)
        // 转选题现在按**文章** id 加载——选题挂在文章上，不是挂在记录上
        vm.load("a_n1")
        assertEquals("应该拆出角度", 1, vm.state.value.angles.size)

        vm.chooseAngle(vm.state.value.angles.first())
        assertEquals(TopicStep.OUTLINE, vm.state.value.step)

        vm.saveScript()

        val saved = scriptDao.findAll()
        assertEquals("应存下一条选题", 1, saved.size)
        assertEquals(
            "选题必须挂在真实文章 id 上，否则选题 tab 永远看不到它",
            "a_n1",
            saved.first().sourceArticleId,
        )
        assertNotNull("存完要立刻切到已保存视图", vm.state.value.existingScript)
        assertTrue(vm.state.value.hasExisting)
    }

    @Test
    fun `没有文章时不硬造选题`() = runTest {
        seedNote()
        val vm = TopicViewModel(repo, ai)
        vm.load("n1")
        vm.chooseAngle(Angle("角度", "理由", "kind"))
        vm.saveScript()

        assertTrue("没有文章就不该存选题", scriptDao.findAll().isEmpty())
    }
}
