package com.hatio.speakinspire

import com.hatio.speakinspire.service.Prompts
import com.hatio.speakinspire.service.SproutContext
import com.hatio.speakinspire.service.SproutResult
import com.hatio.speakinspire.service.FakeAiService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发芽的边界守卫。
 *
 * ## 为什么发芽需要单独的边界测试
 *
 * 追问的规矩是「只问不答」，发芽的规矩正好相反——它**必须给东西**。
 * 这两条规则互斥，所以不能用同一套断言。
 *
 * 但发芽有它自己的红线，而且更危险：
 * 追问给不出答案，用户顶多想不出来；发芽给一个**编造的引用**，
 * 用户可能会真的去找那本书。找不到，这个 App 说的所有话就都不可信了。
 *
 * 所以这里守三条：
 * 1. 每一节必须从用户的原话出发（seed 不能是模型自己写的）
 * 2. 引用必须有出处；没把握就留空，**不许编**
 * 3. 给的是参照物，不是结论——不许出现「你应该」
 */
class SproutBoundaryTest {

    private val p: String get() = Prompts.SPROUT_SYSTEM_PROMPT

    @Test
    fun `发芽必须给内容，不是只提问`() {
        assertTrue("必须明说「给出东西」", p.contains("你要给出东西"))
        assertTrue("必须定性为参照物而非结论", p.contains("参照物"))
        // 反证：如果这里出现了追问那句「只输出一个开放式问题」，
        // 说明两个 prompt 被抄混了——发芽会退化成第二个追问
        assertFalse(
            "发芽不能沿用追问的输出约束",
            p.contains("只输出一个开放式问题"),
        )
    }

    @Test
    fun `seed 必须来自用户的原话`() {
        assertTrue("必须要求原样摘句", p.contains("原样摘一句"))
        assertTrue("必须禁止自己另起一句", p.contains("不许自己另起一句"))
    }

    @Test
    fun `引用必须有出处，编不出来就留空`() {
        assertTrue("必须给出留空这条出路", p.contains("source 留成空字符串"))
        assertTrue("必须禁止编造", p.contains("绝对不许编造书名、人名、数据、年份"))
        // 这条是给用户看的理由，模型也需要知道「为什么不能编」
        assertTrue("必须说明编造的代价", p.contains("比什么都不说坏一百倍"))
    }

    @Test
    fun `发芽不许替用户下结论`() {
        assertTrue("必须禁止给建议", p.contains("这不是你的活"))
        assertTrue("必须禁止对人格下判断", p.contains("不对他的人格下判断"))
    }

    @Test
    fun `已经长过的芽会被写进 prompt`() {
        val withHistory = Prompts.sproutSystem(listOf("第一次长出来的东西"))
        assertTrue(
            "不把旧的芽告诉模型，第二次大概率给出同一批连接",
            withHistory.contains("第一次长出来的东西"),
        )
        assertTrue(withHistory.contains("这次换个方向"))

        // 没有历史时不该出现这一段，否则模型会以为「之前长过」却看不到是什么
        assertFalse(p.contains("他已经长过这些芽了"))
    }

    @Test
    fun `假的发芽也必须从用户原话里摘 seed`() = runBlocking {
        val fake = FakeAiService()
        val body = "我提前背了稿子，上台全忘了"
        val r = fake.sprout(
            SproutContext(noteId = "n1", noteTitle = "紧张", noteBody = body)
        )
        assertTrue(r is SproutResult.Success)
        val first = (r as SproutResult.Success).sections.first()
        assertTrue(
            "假实现也要守住 seed 来源——否则测试会放过『seed 是模型编的』",
            body.startsWith(first.seed) || body.contains(first.seed),
        )
    }

    @Test
    fun `发芽失败会把原因带出去`() = runBlocking {
        val fake = FakeAiService()
        fake.failSprout = true
        val r = fake.sprout(SproutContext(noteId = "n1", noteTitle = "", noteBody = "一句话"))
        assertTrue(r is SproutResult.Failed)
        assertEquals("模拟的发芽失败", (r as SproutResult.Failed).detail)
    }
}
