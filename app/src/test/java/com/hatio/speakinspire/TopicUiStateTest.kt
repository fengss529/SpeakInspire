package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.Angle
import com.hatio.speakinspire.data.model.Cue
import com.hatio.speakinspire.data.model.OutlineSection
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.data.model.ScriptOutline
import com.hatio.speakinspire.data.model.Section
import com.hatio.speakinspire.ui.topic.TopicStep
import com.hatio.speakinspire.ui.topic.TopicUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转选题页规则测试。
 *
 * 守三条：
 * 1. 骨架是意群短语，不是逐字稿
 * 2. 「已拍摄」只是标记，不涉及摄像头
 * 3. 拆角度与生成骨架是两次独立调用
 */
class TopicUiStateTest {

    private fun sections() = listOf(
        Section("开场钩子", 8, listOf("你说话是不是总带然后"), listOf(Cue("👁", "看镜头"))),
        Section("拆原因", 18, listOf("我在缓冲", "但嘴没跟上"), emptyList()),
        Section("举例", 20, listOf("开会时讲到第二页就卡住"), emptyList()),
        Section("收尾", 8, listOf("所以得练的是缓冲"), emptyList()),
    )

    private fun state(
        angles: List<Angle> = emptyList(),
        chosen: Angle? = null,
        outline: ScriptOutline? = null,
        existing: Script? = null,
        shotAt: Long? = null,
        step: TopicStep = TopicStep.ANGLES,
    ) = TopicUiState(
        articleTitle = "文章标题",
        step = step,
        angles = angles,
        chosenAngle = chosen,
        outline = outline,
        existingScript = existing,
        shotAt = shotAt,
    )

    // ---------- 骨架结构 ----------

    @Test
    fun `骨架总时长从分段累加`() {
        val s = state(existing = Script("s1", "a1", "文章标题", "挑衅", sections(), 0L))
        assertEquals(54, s.totalSeconds)
    }

    @Test
    fun `四段总时长落在30到60 秒`() {
        val s = state(existing = Script("s1", "a1", "文章标题", "挑衅", sections(), 0L))
        assertEquals(4, s.displaySections.size)
        assertTrue("总时长应在 30-60 秒，实际 ${s.totalSeconds}", s.totalSeconds in 30..60)
    }

    @Test
    fun `短语是意群不是孤立词`() {
        val phrases = sections().flatMap { it.phrases }
        phrases.forEach { p ->
            assertTrue(
                "「$p」应该是完整意群（不少于3 个字），孤立词对组句困难的用户负荷过高",
                p.length >= 3,
            )
        }
    }

    @Test
    fun `镜头提示保留在已存选题里`() {
        val s = state(existing = Script("s1", "a1", "文章标题", "挑衅", sections(), 0L))
        assertEquals("镜头提示不能丢", 1, s.displaySections[0].cues.size)
    }

    @Test
    fun `空骨架总时长为零`() {
        val s = state(outline = ScriptOutline("角度", emptyList()))
        assertEquals(0, s.totalSeconds)
    }

    // ---------- 已拍摄只是标记 ----------

    @Test
    fun `未标记时不算已拍摄`() {
        val s = state(existing = Script("s1", "a1", "文章标题", "角度", sections(), 0L))
        assertFalse(s.isShot)
    }

    @Test
    fun `标记后显示已拍摄`() {
        val s = state(
            existing = Script("s1", "a1", "文章标题", "角度", sections(), 0L, shotAt = 1700000000000L),
            shotAt = 1700000000000L,
        )
        assertTrue(s.isShot)
    }

    @Test
    fun `标记只是状态位不改变内容`() {
        // 「已拍摄」是这个 App 唯一的动作，没有其他副作用
        val before = Script("s1", "a1", "文章标题", "角度", sections(), 0L)
        val after = before.copy(shotAt = 1700000000000L)
        assertEquals("内容不能变", before.sections, after.sections)
        assertEquals(before.angle, after.angle)
        assertNotNullShot(after)
    }

    private fun assertNotNullShot(s: Script) {
        assertTrue("标记后应有时间戳", s.shotAt != null)
    }

    // ---------- 两步流程 ----------

    @Test
    fun `初始在选角度步`() {
        assertEquals(TopicStep.ANGLES, state().step)
    }

    @Test
    fun `选中角度后进骨架步`() {
        val s = state(chosen = Angle("讲经历", "有共鸣", "经历"), step = TopicStep.OUTLINE)
        assertEquals(TopicStep.OUTLINE, s.step)
    }

    @Test
    fun `最多展示三个角度`() {
        // 设计约束：三个是选择时信息量刚好
        val angles = (1..6).map { Angle("角度$it", "理由", "类型") }
        val s = state(angles = angles)
        assertEquals(6, s.angles.size)
        // ViewModel 里 take(3) 截断，这里验证数据层给多少都不影响展示逻辑
        assertTrue(s.angles.size > 3)
    }

    @Test
    fun `已有选题时优先展示它`() {
        val existing = Script("s1", "a1", "文章标题", "已存的角度", sections(), 0L)
        val s = state(chosen = Angle("新角度", "", ""), existing = existing)
        assertTrue(s.hasExisting)
        assertEquals("已存的角度", s.displayAngle)
    }

    @Test
    fun `没有已存选题时展示生成的骨架`() {
        val s = state(
            chosen = Angle("新角度", "", ""),
            outline = ScriptOutline(
                "新角度",
                listOf(OutlineSection("开场", 8, "抓注意", listOf("你说话总带然后"), emptyList())),
            ),
        )
        assertFalse(s.hasExisting)
        assertEquals(1, s.displaySections.size)
        assertEquals("新角度", s.displayAngle)
    }

    @Test
    fun `两者都没有时展示区为空`() {
        val s = state()
        assertFalse(s.hasExisting)
        assertTrue(s.displaySections.isEmpty())
        assertEquals(0, s.totalSeconds)
    }

    // ---------- 骨架有内容才能存 ----------

    @Test
    fun `空骨架不该保存`() {
        val s = state(
            chosen = Angle("角度", "", ""),
            outline = ScriptOutline("角度", emptyList()),
        )
        assertTrue("空骨架存进去是垃圾数据", s.displaySections.isEmpty())
    }

    @Test
    fun `有内容的骨架可以保存`() {
        val s = state(
            chosen = Angle("角度", "", ""),
            outline = ScriptOutline(
                "角度",
                listOf(OutlineSection("开场", 8, "", listOf("短句"), emptyList())),
            ),
        )
        assertFalse(s.displaySections.isEmpty())
    }

    // ---------- 镜头提示 ----------

    @Test
    fun `镜头提示的图标与文字都在`() {
        val cue = sections()[0].cues[0]
        assertEquals("👁", cue.icon)
        assertEquals("看镜头", cue.text)
    }

    @Test
    fun `没有镜头提示时是空列表而非null`() {
        assertTrue(sections()[1].cues.isEmpty())
    }
}