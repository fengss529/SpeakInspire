package com.hatio.speakinspire

import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.ui.compose.ComposeUiState
import com.hatio.speakinspire.ui.compose.RejectedInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成文页规则测试。
 *
 * 守住两条产品边界：
 * 1. 来源视图是「允许 AI 补论证」的必要配套
 * 2. 拒稿不是失败，不能变成空文章
 */
class ComposeUiStateTest {

    private fun state(
        title: String = "标题",
        paragraphs: List<Paragraph> = emptyList(),
        sourceMode: Boolean = true,
        rejected: RejectedInfo? = null,
        loading: Boolean = false,
    ) = ComposeUiState(
        noteId = "n1",
        title = title,
        paragraphs = paragraphs,
        sourceMode = sourceMode,
        rejected = rejected,
        loading = loading,
    )

    // ---------- 来源视图 ----------

    @Test
    fun `统计你说的段落数`() {
        val s = state(paragraphs = listOf(
            Paragraph("我说的1", true),
            Paragraph("AI补的", false),
            Paragraph("我说的2", true),
        ))
        assertEquals(2, s.mineCount)
        assertEquals(1, s.aiCount)
    }

    @Test
    fun `全是用户的话时AI 补数为零`() {
        val s = state(paragraphs = listOf(
            Paragraph("都是你说的", true),
            Paragraph("也是你说的", true),
        ))
        assertEquals(0, s.aiCount)
        assertEquals(2, s.mineCount)
    }

    @Test
    fun `来源视图默认开启`() {
        // 默认关闭等于默认不标注——那「允许 AI 补」就等于「可以编」
        assertTrue(state(sourceMode = true).sourceMode)
    }

@Test
fun `切到纯文章视图时来源标注隐藏`() {
        val s = state(
            paragraphs = listOf(Paragraph("你说的", true), Paragraph("AI 补的", false)),
            sourceMode = false,
        )
        assertFalse("切换后应隐藏标注", s.sourceMode)
        // 但段落内容不变
        assertEquals(2, s.paragraphs.size)
        assertEquals(1, s.mineCount)
    }

    @Test
    fun `段落标记不因视图切换而改变`() {
        // 切视图只是隐藏标注，不改变数据本身
        val paras = listOf(Paragraph("A", true), Paragraph("B", false))
        val on = state(paragraphs = paras, sourceMode = true)
        val off = state(paragraphs = paras, sourceMode = false)
        assertEquals(on.mineCount, off.mineCount)
        assertEquals(on.aiCount, off.aiCount)
    }

    // ---------- 拒稿 ----------

    @Test
    fun `拒稿时不产生任何段落`() {
        val s = state(title = "", paragraphs = emptyList(), rejected = RejectedInfo(200, 300))
        assertFalse("拒稿绝不能返回空文章", s.hasContent)
    }

    @Test
    fun `拒稿时算出差多少字`() {
        assertEquals(100, RejectedInfo(200, 300).gap)
    }

    @Test
    fun `已达标时差值为零`() {
        assertEquals(0, RejectedInfo(350, 300).gap)
    }

    @Test
    fun `超出时不出现负数`() {
        assertEquals(0, RejectedInfo(500, 300).gap)
    }

    @Test
    fun `拒稿话术不说「你还没说够」`() {
        val msg = RejectedInfo(200, 300).message
        assertFalse("不该让用户觉得是自己的问题", msg.contains("还没说够"))
        assertFalse(msg.contains("不足"))
    }

    @Test
    fun `拒稿话术给出路`() {
        val msg = RejectedInfo(200, 300).message
        assertTrue("必须告诉用户接下来怎么办", msg.contains("聊"))
        assertTrue("要说清差多少", msg.contains("100"))
    }

    @Test
    fun `拒稿不阻塞重试`() {
        // 拒稿后用户可以去追问页再聊聊，所以不能是终态错误
        val s = state(rejected = RejectedInfo(200, 300))
        assertEquals(RejectedInfo(200, 300), s.rejected)
    }

    // ---------- 保存条件 ----------

    @Test
    fun `空标题不能保存`() {
        assertFalse(state(title = "", paragraphs = listOf(Paragraph("x", true))).canSave)
    }

    @Test
    fun `空段落不能保存`() {
        assertFalse(state(title = "标题", paragraphs = emptyList()).canSave)
    }

    @Test
    fun `有标题有段落才能保存`() {
        assertTrue(state(title = "标题", paragraphs = listOf(Paragraph("x", true))).canSave)
    }

    @Test
    fun `加载中不能保存`() {
        assertFalse(state(paragraphs = listOf(Paragraph("x", true)), loading = true).canSave)
    }

    @Test
    fun `拒稿后仍可保存旧文章`() {
        // 如果之前有成文，新一轮被拒稿不该让用户丢掉已有的
        val s = state(paragraphs = listOf(Paragraph("旧内容", true)), rejected = RejectedInfo(200, 300))
        assertTrue("已有内容就该能存", s.canSave)
    }
}