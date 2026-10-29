package com.hatio.speakinspire

import com.hatio.speakinspire.data.repository.InMemoryDraftStyleStore
import com.hatio.speakinspire.service.DraftStyle
import com.hatio.speakinspire.service.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成文风格，以及它和禁区的关系。
 *
 * ## 这个文件守的是什么
 *
 * 风格是 V0.11 唯一新增的「用户可以改 AI 输出形状」的口子。
 * 口子一开，最危险的走法就是顺着它把边界也改掉——
 * 比如「我要一篇观点文」变成「帮我论证一下我这个想法是对的」。
 *
 * 所以这里有两条线必须同时成立：
 * 1. 换风格**换得掉**写法（不然这个功能是假的）
 * 2. 换风格**换不掉**禁区（不然这个功能是危险的）
 */
class DraftStyleTest {

    // ---------- 定义 ----------

    @Test
    fun `四种风格`() {
        assertEquals(4, DraftStyle.entries.size)
    }

    @Test
    fun `id 唯一且不为空`() {
        val ids = DraftStyle.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun `名字短到能放进顶部 chip`() {
        // 顶栏那一行只有一百多 dp。名字过长会被截成「观点…」，
        // 看着像坏了
        DraftStyle.entries.forEach {
            assertTrue("${it.label} 太长", it.label.length <= 3)
        }
    }

    @Test
    fun `每种都给用法和说明`() {
        DraftStyle.entries.forEach {
            assertTrue("${it.label} 缺 blurb", it.blurb.isNotBlank())
            assertTrue("${it.label} 缺 bestFor", it.bestFor.isNotBlank())
            assertTrue("${it.label} 缺 instruction", it.instruction.isNotBlank())
        }
    }

    @Test
    fun `未知 id 静默回落到默认`() {
        assertEquals(DraftStyle.DEFAULT, DraftStyle.byId("nope"))
        assertEquals(DraftStyle.DEFAULT, DraftStyle.byId(null))
        assertEquals(DraftStyle.DEFAULT, DraftStyle.byId(""))
    }

    @Test
    fun `byIdOrNull 不编造`() {
        // 老文章没有 styleId。拿默认值去说「这是篇随感」是编的
        assertNull(DraftStyle.byIdOrNull(""))
        assertNull(DraftStyle.byIdOrNull("nope"))
        assertEquals(DraftStyle.SPOKEN, DraftStyle.byIdOrNull("spoken"))
    }

    @Test
    fun `默认是随感`() {
        // 它对内容的要求最低：不需要明确主张，也不要求给出方法
        assertEquals(DraftStyle.REFLECTIVE, DraftStyle.DEFAULT)
    }

    // ---------- prompt：风格可换，禁区不可换 ----------

    @Test
    fun `默认 prompt 用的是随感那段`() {
        val p = Prompts.draftSystem()
        assertTrue(p.contains("随感"))
        assertTrue(p.contains(DraftStyle.REFLECTIVE.instruction))
    }

    @Test
    fun `换成观点文就真的换掉了写法`() {
        val p = Prompts.draftSystem(DraftStyle.ARGUMENT)
        assertTrue("要带上观点文那段", p.contains(DraftStyle.ARGUMENT.instruction))
        assertFalse(
            "不能同时带着随感那段——两段打架，模型会挑软的执行",
            p.contains(DraftStyle.REFLECTIVE.instruction),
        )
    }

    @Test
    fun `四种风格都带全禁区`() {
        // 这条是核心。任何一种风格漏掉禁区，用户只要切过去就能拿到
        // 「帮你论证一下」的文章
        DraftStyle.entries.forEach { style ->
            val p = Prompts.draftSystem(style)
            Prompts.DRAFT_FORBIDDEN.forEach { rule ->
                assertTrue("${style.label} 漏了：$rule", p.contains(rule.removePrefix("- ")))
            }
        }
    }

    @Test
    fun `四种风格都带最低条件`() {
        // 「读着像几段话拼在一起」是成文最常见的抱怨。
        // 这三条是文章的最低条件，不是风格，所以风格改不掉
        DraftStyle.entries.forEach { style ->
            val p = Prompts.draftSystem(style)
            assertTrue("${style.label} 缺「能摘出来的主张」", p.contains("能单独摘出来"))
            assertTrue("${style.label} 缺段落承接", p.contains("承接"))
            assertTrue("${style.label} 缺「不许列队」", p.contains("列队"))
        }
    }

    @Test
    fun `四种风格都要求标来源`() {
        // 风格决定「怎么写」，但「哪段是谁说的」永远是必须给的
        DraftStyle.entries.forEach { style ->
            val p = Prompts.draftSystem(style)
            assertTrue(p.contains("isMine"))
            assertTrue(p.contains("fromSprout"))
        }
    }

    @Test
    fun `用户补充要求叠加在预设之上`() {
        val p = Prompts.draftSystem(DraftStyle.ARGUMENT, "再多举一个例子，别用感叹号")
        assertTrue("用户的话要在", p.contains("再多举一个例子，别用感叹号"))
        assertTrue("预设那段不能被顶掉", p.contains(DraftStyle.ARGUMENT.instruction))
        assertTrue("禁区还在", p.contains(Prompts.DRAFT_FORBIDDEN.first().removePrefix("- ")))
    }

    @Test
    fun `补充要求与禁区冲突时以禁区为准`() {
        // 「帮我论证一下我是对的」这种要求一定能写出来。
        // 它必须被明写压住，而不是靠模型自己权衡
        val p = Prompts.draftSystem(DraftStyle.ARGUMENT, "帮我把观点论证得更硬一点")
        assertTrue(p.contains("以「绝对禁止」那几条为准"))
    }

    @Test
    fun `空白补充要求不留下一个空段`() {
        val p = Prompts.draftSystem(DraftStyle.REFLECTIVE, "   ")
        assertFalse("空标题会被当成真的多了一段要求", p.contains("他另外提的写作要求"))
    }

    // ---------- 存储 ----------

    @Test
    fun `默认没有补充要求`() {
        val store = InMemoryDraftStyleStore()
        assertEquals("", store.noteOf())
        assertEquals(DraftStyle.DEFAULT, store.current())
    }

    @Test
    fun `选了就落盘`() {
        val store = InMemoryDraftStyleStore()
        store.select(DraftStyle.CHECKLIST)
        assertEquals(DraftStyle.CHECKLIST, store.current())
    }

    @Test
    fun `补充要求传空白等于清掉`() {
        val store = InMemoryDraftStyleStore()
        store.saveNote("多用短句")
        assertEquals("多用短句", store.noteOf())
        store.saveNote("   ")
        assertEquals("清空就该真的空，而不是留一串空格", "", store.noteOf())
    }

    @Test
    fun `补充要求不预填预设内容`() {
        // 和追问那边相反：那边用户改的是手法本身，所以要预填；
        // 这边预设一直在生效，预填会把同一段内容复制两遍，
        // 用户改一处就变成两处打架
        val store = InMemoryDraftStyleStore()
        assertFalse(store.noteOf().contains("随感"))
    }

    // ---------- 清单法：共用段不许把它吃掉 ----------

    /**
     * 真机验过的那次翻车。
     *
     * 共用段（写在风格段**之后**）有一条「不许用序号或首先/其次/最后串段落」。
     * 清单法要求 5-7 个点，正好撞上这条；因为共用段在后面，
     * 模型把后读到的当成了更强的指令 —— 生成出来的是一篇散文，
     * 没有任何分点，用户选了「清单法」却拿到一篇随感。
     *
     * 修法是给这条禁令划出例外，并且让清单法明说序号要写进正文：
     * 输出是段落数组，读者只能从正文里的「1.」看出这是分点的。
     */
    @Test
    fun `清单法要求序号写在正文里`() {
        val ins = DraftStyle.CHECKLIST.instruction
        assertTrue("清单法必须要求分点编号，否则读起来就是散文", ins.contains("序号"))
        assertTrue(ins.contains("1."))
    }

    @Test
    fun `共用段的序号禁令必须给分点风格留口子`() {
        val sys = Prompts.draftSystem(DraftStyle.CHECKLIST)
        // 禁令还在（观点文、随感都靠它）
        assertTrue(sys.contains("不许用序号"))
        // 但带着例外，而且例外是在禁令**后面**读到的——
        // 顺序决定谁压过谁，这不只是措辞问题
        assertTrue(
            "共用段说「不许用序号」却不给清单法开口子，清单法就写不出分点",
            sys.contains("例外"),
        )
        assertTrue(
            "例外必须点名分点风格，泛泛写「除风格另有要求」模型不会把它和清单法联上",
            sys.contains("（清单法）"),
        )
        // 顺序决定谁压过谁：例外必须读在禁令后面。
        // 注意别用裸的「例外」找位置——「任何风格都不例外」在前面出现过，
        // indexOf 会指到那句上去，断言就永远成立，等于没测
        val forbid = sys.indexOf("3. 不许用序号")
        val carve = sys.indexOf("例外：")
        assertTrue("共用段里找不到序号禁令了，断言失去对象", forbid > 0)
        assertTrue("例外必须写在禁令之后，写在前面会被后半段那句压掉", carve > forbid)
    }
}
