package com.hatio.speakinspire

import com.hatio.speakinspire.data.local.NoteEntity
import com.hatio.speakinspire.data.local.SimpleJson
import com.hatio.speakinspire.data.local.toEntity
import com.hatio.speakinspire.data.local.toModel
import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Cue
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.Note
import com.hatio.speakinspire.data.model.NoteSource
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.data.model.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 映射层测试。
 *
 * 重点在 JSON 转义——用户原话里出现引号是常态，
 * 转义错了会导致数据**静默损坏**，这种 bug 极难排查。
 */
class MappersTest {

    // ---------- 追问轮次 ----------

    @Test
    fun `轮次往返一致`() {
        val rounds = listOf(
            Round("当时具体发生了什么？", "讲到第二页就卡住了"),
            Round("你怎么解释私底下聊得挺好？", "没有观众就没压力"),
        )
        assertEquals(rounds, SimpleJson.decodeRounds(SimpleJson.encodeRounds(rounds)))
    }

    @Test
    fun `空轮次往返`() {
        assertEquals(emptyList<Round>(), SimpleJson.decodeRounds(SimpleJson.encodeRounds(emptyList())))
    }

    @Test
    fun `未回答的轮次保持 null`() {
        val rounds = listOf(Round("问题？", null))
        assertNull(SimpleJson.decodeRounds(SimpleJson.encodeRounds(rounds))[0].answer)
    }

    @Test
    fun `answered 判定空字符串为未回答`() {
        assertTrue(!Round("问题", "").answered)
        assertTrue(Round("问题", "答").answered)
    }

    // ---------- 转义：这是最容易出错的地方 ----------

    @Test
    fun `用户原话含引号时不损坏`() {
        val rounds = listOf(Round("他说的是\"这样\"，你觉得呢？", "我觉得他在\"引用\"我"))
        val decoded = SimpleJson.decodeRounds(SimpleJson.encodeRounds(rounds))
        assertEquals(rounds, decoded)
    }

    @Test
    fun `用户原话含反斜杠时不损坏`() {
        val rounds = listOf(Round("路径是 C:\\Users\\test 吗？", "对，还有 \\n 是换行符"))
        assertEquals(rounds, SimpleJson.decodeRounds(SimpleJson.encodeRounds(rounds)))
    }

    @Test
    fun `用户原话含换行时不损坏`() {
        val rounds = listOf(
            Round("问题？", "第一行\n第二行\r\n第三行"),
        )
        assertEquals(rounds, SimpleJson.decodeRounds(SimpleJson.encodeRounds(rounds)))
    }

    @Test
    fun `用户原话含制表符时不损坏`() {
        val rounds = listOf(Round("q", "有\t制表符"))
        assertEquals(rounds, SimpleJson.decodeRounds(SimpleJson.encodeRounds(rounds)))
    }

    @Test
    fun `逗号在字符串内不会破坏切分`() {
        val rounds = listOf(Round("问题？", "他说：这样, 然后那样, 最后这样"))
        assertEquals(rounds, SimpleJson.decodeRounds(SimpleJson.encodeRounds(rounds)))
    }

    @Test
    fun `多个轮次带逗号仍正确切分`() {
        val rounds = listOf(
            Round("第一问，含逗号, 真的", "答一，有逗号"),
            Round("第二问", "答二"),
            Round("第三问", "答三"),
        )
        val decoded = SimpleJson.decodeRounds(SimpleJson.encodeRounds(rounds))
        assertEquals(3, decoded.size)
        assertEquals(rounds, decoded)
    }

    @Test
    fun `中文与英文混排正常`() {
        val rounds = listOf(Round("你说的「垫词」是指 then/now 吗？", "对，中英混着来"))
        assertEquals(rounds, SimpleJson.decodeRounds(SimpleJson.encodeRounds(rounds)))
    }

    @Test
    fun `emoji 不被破坏`() {
        val rounds = listOf(Round("这个👍 怎么说", "还行😊"))
        assertEquals(rounds, SimpleJson.decodeRounds(SimpleJson.encodeRounds(rounds)))
    }

    // ---------- 段落 ----------

    @Test
    fun `段落往返保留 mine 标记`() {
        val paras = listOf(
            Paragraph("我说过的", true),
            Paragraph("AI 补的", false),
        )
        assertEquals(paras, SimpleJson.decodeParagraphs(SimpleJson.encodeParagraphs(paras)))
    }

    @Test
    fun `段落顺序保持不变`() {
        val paras = (1..5).map { i -> Paragraph("第${i}段", i % 2 == 0) }
        val decoded = SimpleJson.decodeParagraphs(SimpleJson.encodeParagraphs(paras))
        assertEquals(paras, decoded)
    }

    @Test
    fun `段落含引号不损坏`() {
        val paras = listOf(Paragraph("他说\"这是重点\"，我补充一下", false))
        assertEquals(paras, SimpleJson.decodeParagraphs(SimpleJson.encodeParagraphs(paras)))
    }

    // ---------- 骨架（嵌套结构：最容易出问题）----------

    @Test
    fun `骨架往返一致`() {
        val sections = listOf(
            Section("开场钩子", 8, listOf("你说话是不是总带然后"), listOf(Cue("👁", "看向镜头"))),
            Section("拆原因", 18, listOf("我在缓冲", "但嘴没跟上", "所以卡住"), emptyList()),
        )
        assertEquals(sections, SimpleJson.decodeSections(SimpleJson.encodeSections(sections)))
    }

    @Test
    fun `骨架嵌套的空 cues 正确保留`() {
        val sections = listOf(Section("收尾", 5, listOf("就这样"), emptyList()))
        val decoded = SimpleJson.decodeSections(SimpleJson.encodeSections(sections))
        assertNotNull(decoded)
        assertEquals(emptyList<Cue>(), decoded[0].cues)
    }

    @Test
    fun `骨架短语含逗号不破坏切分`() {
        val sections = listOf(
            Section("开场", 8, listOf("第一句, 有逗号", "第二句, 也有"), emptyList()),
        )
        val decoded = SimpleJson.decodeSections(SimpleJson.encodeSections(sections))
        assertEquals(sections[0].phrases, decoded[0].phrases)
    }

    @Test
    fun `镜头提示不被丢失`() {
        // 曾经的 bug：cues 是对象数组，却用 extractString 解析，永远返回 null，
        // 导致镜头提示静默消失。这类bug 不报错，只是功能不见了。
        val sections = listOf(
            Section("开场", 8, listOf("你说话是不是总带然后"), listOf(Cue("👁", "看向镜头"))),
        )
        val decoded = SimpleJson.decodeSections(SimpleJson.encodeSections(sections))
        assertEquals(1, decoded[0].cues.size)
        assertEquals("👁", decoded[0].cues[0].icon)
        assertEquals("看向镜头", decoded[0].cues[0].text)
    }

    @Test
    fun `单个短语的数组不被误切`() {
        // 曾经的 bug：字符串数组复用了对象切分逻辑，
        // 导致 "第一句, 有逗号" 被切成两条
        val sections = listOf(Section("a", 5, listOf("只有一句"), emptyList()))
        assertEquals(1, SimpleJson.decodeSections(SimpleJson.encodeSections(sections))[0].phrases.size)
    }

    @Test
    fun `多段镜头提示都保留`() {
        val sections = listOf(
            Section("开场", 8, listOf("短句"), listOf(Cue("⏸", "停顿"), Cue("👁", "看镜头"))),
        )
        val decoded = SimpleJson.decodeSections(SimpleJson.encodeSections(sections))
        assertEquals(2, decoded[0].cues.size)
    }

    @Test
    fun `镜头提示含逗号不损坏`() {
        val sections = listOf(
            Section("开场", 8, listOf("短句"), listOf(Cue("✋", "手势：这里, 比划一下"))),
        )
        val decoded = SimpleJson.decodeSections(SimpleJson.encodeSections(sections))
        assertEquals("手势：这里, 比划一下", decoded[0].cues[0].text)
    }

    @Test
    fun `短语含引号不损坏`() {
        val sections = listOf(
            Section("开场", 8, listOf("他说\"这就是关键\""), emptyList()),
        )
        val decoded = SimpleJson.decodeSections(SimpleJson.encodeSections(sections))
        assertEquals("他说\"这就是关键\"", decoded[0].phrases[0])
    }

    @Test
    fun `总时长可从分段累加`() {
        val sections = listOf(
            Section("a", 8, emptyList()),
            Section("b", 20, emptyList()),
            Section("c", 12, emptyList()),
            Section("d", 6, emptyList()),
        )
        assertEquals(46, sections.sumOf { it.seconds })
    }

    @Test
    fun `四个分段总时长落在30到60 秒区间`() {
        // 结构决策：4 段、总时长 30-60 秒
        val sections = listOf(
            Section("开场钩子", 8, emptyList()),
            Section("展开", 18, emptyList()),
            Section("拆解", 20, emptyList()),
            Section("收尾", 8, emptyList()),
        )
        val total = sections.sumOf { it.seconds }
        assertTrue("总时长应在 30-60 秒，实际 $total", total in 30..60)
    }

    // ---------- Entity ↔ Model ----------

    @Test
    fun `note 往返一致`() {
        val note = Note("n1", "标题", "正文内容", 1700000000000L, NoteSource.INSIGHT, "n0")
        assertEquals(note, note.toEntity().toModel())
    }

    @Test
    fun `note source 未知值时兜底为 QUICK`() {
        // 将来新增枚举值时，旧库里的数据不该让列表崩掉
        val entity = NoteEntity("n1", "t", "b", 0L, "FUTURE_SOURCE", null)
        assertEquals(NoteSource.QUICK, entity.toModel().source)
    }

    @Test
    fun `deepening 往返一致`() {
        val d = Deepening(
            "d1", "n1",
            listOf(Round("问？", "答"), Round("问2？", "答2")),
            1700000000000L, 1700000100000L, 5,
        )
        assertEquals(d, d.toEntity().toModel())
    }

    @Test
    fun `deepening 未收尾时finishedAt 保持 null`() {
        val d = Deepening("d1", "n1", listOf(Round("问？", null)), 1700000000000L)
        val back = d.toEntity().toModel()
        assertNull(back.finishedAt)
        assertEquals(1, back.rounds.size)
    }

    @Test
    fun `article 往返保留段落来源标记`() {
        val a = Article(
            "a1", "n1", "标题",
            listOf(Paragraph("我说的", true), Paragraph("AI 补的", false)),
            1700000000000L, edited = true,
        )
        val back = a.toEntity().toModel()
        assertEquals(a, back)
        assertTrue(back.paragraphs.count { !it.mine } == 1)
    }

    @Test
    fun `article 编辑后 edited 标记为 true`() {
        val a = Article("a1", "n1", "t", listOf(Paragraph("x", true)), 0L)
        assertTrue(!a.toEntity().toModel().edited)
    }

    @Test
    fun `script 往返保留拍摄与搁置状态`() {
        val s = Script(
            "s1", "a1", "直接挑衅",
            listOf(Section("开场", 8, listOf("你说话是不是总带然后"))),
            1700000000000L,
            shotAt = 1700000200000L,
            parked = false,
            note = "备注内容",
        )
        val back = s.toEntity().toModel()
        assertEquals(s, back)
        assertNotNull(back.shotAt)
    }

    @Test
    fun `未拍摄的 script shotAt 为 null`() {
        val s = Script("s1", "a1", "角度", emptyList(), 0L)
        assertNull(s.toEntity().toModel().shotAt)
    }

    @Test
    fun `左滑搁置后 parked 为 true`() {
        val s = Script("s1", "a1", "角度", emptyList(), 0L, parked = true)
        assertTrue(s.toEntity().toModel().parked)
    }
}