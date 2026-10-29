package com.hatio.speakinspire

import com.hatio.speakinspire.data.local.SimpleJson
import com.hatio.speakinspire.data.local.toEntity
import com.hatio.speakinspire.data.local.toModel
import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Material
import com.hatio.speakinspire.data.model.MaterialKind
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.data.model.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 来源三档，以及 v5 加的两列 JSON 往返。
 *
 * ## 第三档为什么必须存在
 *
 * 「来源」视图原来是两档：你说的 / AI 补。接上发芽之后不够用了——
 * 芽里是**别人的观点、例子、引用**。它们既不是用户说的，
 * 也不是 AI 为了通顺补的论证。
 *
 * 混进「AI 补」，用户会以为那只是通用论证；
 * 混进「你说的」，就是造假。所以它必须单独一档，
 * 并且**恒为 mine = false**。
 */
class ParagraphSourceTest {

    // ---------- 三档标签 ----------

    @Test
    fun `用户说的是三档里的第一档`() {
        assertEquals("你说的", Paragraph("我说的", mine = true).sourceLabel)
    }

    @Test
    fun `AI 补的论证`() {
        assertEquals("AI 补", Paragraph("补的", mine = false).sourceLabel)
    }

    @Test
    fun `来自发芽的单独一档`() {
        val p = Paragraph("爱因斯坦也说过类似的话", mine = false, fromSprout = true)
        assertEquals("AI 补 · 含他人观点", p.sourceLabel)
    }

    @Test
    fun `发芽段绝不显示成你说的`() {
        // 即使有人把两个字段都写成 true（模型偶尔会），
        // 标签也只能走「他人观点」那一条
        val p = Paragraph("别人的话", mine = true, fromSprout = true)
        assertEquals("AI 补 · 含他人观点", p.sourceLabel)
    }

    // ---------- 存储往返 ----------

    @Test
    fun `三个字段都能往返`() {
        val paras = listOf(
            Paragraph("我说的", mine = true),
            Paragraph("AI 补的", mine = false),
            Paragraph("芽里来的", mine = false, fromSprout = true),
        )
        assertEquals(paras, SimpleJson.decodeParagraphs(SimpleJson.encodeParagraphs(paras)))
    }

    @Test
    fun `v5 之前的段落没有 s 字段也能读`() {
        // 老文章的 JSON 只有 t 和 m。解码时必须容忍缺字段——
        // 少一个字段就把整段判成坏数据，会让老文章一片空白
        val old = """[{"t":"我早就想说了","m":true},{"t":"补的","m":false}]"""
        val decoded = SimpleJson.decodeParagraphs(old)
        assertEquals(2, decoded.size)
        assertTrue(decoded[0].mine)
        assertFalse("老数据一律不是发芽来的", decoded[0].fromSprout)
        assertFalse(decoded[1].fromSprout)
    }

    @Test
    fun `自相矛盾的组合在读库时被纠正`() {
        // 「别人说的话，标成你说的」是来源视图彻底失效的那种错。
        // 在这里兜一次，免得某天有人从别的路径写进来
        val bad = """[{"t":"别人的话","m":true,"s":true}]"""
        val p = SimpleJson.decodeParagraphs(bad).single()
        assertFalse("不能既是你说的又是他人观点", p.fromSprout)
        assertTrue(p.mine)
        assertEquals("你说的", p.sourceLabel)
    }

    // ---------- 素材往返 ----------

    @Test
    fun `素材四个字段往返`() {
        val list = listOf(
            Material(MaterialKind.EXAMPLE, "上次跟客户吃饭也是", ""),
            Material(MaterialKind.DATA, "67% 的人不敢在会上开口", "某研究，2019"),
        )
        assertEquals(list, SimpleJson.decodeMaterials(SimpleJson.encodeMaterials(list)))
    }

    @Test
    fun `素材文本带引号也能往返`() {
        // 用户和模型都会写「他说"这样"」
        val m = Material(MaterialKind.EXAMPLE, "他说\"这样就行了\"，我照做了")
        assertEquals(m.text, SimpleJson.decodeMaterials(SimpleJson.encodeMaterials(listOf(m))).single().text)
    }

    @Test
    fun `认不出的种类当例子而不是丢掉整条`() {
        val json = """[{"k":"WHATEVER","t":"一条素材","src":""}]"""
        val m = SimpleJson.decodeMaterials(json).single()
        assertEquals(MaterialKind.EXAMPLE, m.kind)
        assertEquals("一条素材", m.text)
    }

    // ---------- 实体往返 ----------

    @Test
    fun `选题素材存进实体再读回来一致`() {
        val script = Script(
            id = "s1",
            angle = "从卡壳讲起",
            sections = listOf(Section("开场", 8, listOf("我准备了两周"))),
            createdAt = 1L,
            materials = listOf(
                Material(MaterialKind.QUOTE, "勇气是压力下的优雅", "海明威"),
            ),
        )
        val back = script.toEntity().toModel()
        assertEquals(script.materials, back.materials)
    }

    @Test
    fun `文章风格存进实体再读回来一致`() {
        val article = Article(
            id = "a1",
            title = "标题",
            paragraphs = listOf(Paragraph("我说的", true)),
            writtenAt = 1L,
            styleId = "spoken",
        )
        assertEquals("spoken", article.toEntity().toModel().styleId)
    }
}
