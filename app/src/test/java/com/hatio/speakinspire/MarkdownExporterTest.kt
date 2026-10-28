package com.hatio.speakinspire

import com.hatio.speakinspire.data.export.MarkdownExporter
import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Cue
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.Note
import com.hatio.speakinspire.data.model.NoteSource
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.data.model.Section
import com.hatio.speakinspire.data.model.Sprout
import com.hatio.speakinspire.data.model.SproutSection
import com.hatio.speakinspire.domain.ArticleView
import com.hatio.speakinspire.domain.Library
import com.hatio.speakinspire.domain.NoteView
import com.hatio.speakinspire.domain.TopicView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出测试。
 *
 * ## 这里守的是「完整性」，不是格式漂亮
 *
 * 用户导出的动机只有一个：**怕东西丢**。
 * 如果导出只是一篇摘要，他下次还得回来翻 App，
 * 那这次导出就没解决任何问题。
 *
 * 所以每一条断言都在问同一个问题：
 * 「少了这段，用户会不会被迫回到 App 里找？」
 */
class MarkdownExporterTest {

    private val t = 1_700_000_000_000L

    private fun fullLibrary() = Library(
        notes = listOf(NoteView(
        note = Note(
            id = "n1",
            title = "上台为什么紧张",
            body = "我提前背了稿子，上台全忘了。",
            createdAt = t,
            source = NoteSource.QUICK,
        ),
        deepening = Deepening(
            id = "d_n1",
            noteId = "n1",
            rounds = listOf(
                Round("背下来的那些字，是卡在嘴边，还是压根没想起来？", "没想起来。"),

                Round("「没想起来」和「忘了」是一回事吗？", null),
            ),
            startedAt = t,
            finishedAt = t + 1000,
        ),
        articleId = "a_n1",
        sprouts = listOf(
            Sprout(
                id = "sp1",
                noteId = "n1",
                title = "背下来的东西不算会",
                sections = listOf(
                    SproutSection(
                        seed = "我提前背了稿子，上台全忘了。",
                        insight = "把话写死之后，大脑会把它当成已经完成的事。",
                        source = "",
                    )
                ),
                createdAt = t + 5000,
            )
        ),
    )),
        articles = listOf(ArticleView(
            article = Article(
                id = "a_n1",
                sourceNoteId = "n1",
                sourceText = "我提前背了稿子，上台全忘了",
                title = "忘词的那三秒",
                paragraphs = listOf(
                    Paragraph("我提前背了稿子。", mine = true),
                    Paragraph("熟练度错觉让人误以为自己准备好了。", mine = false),
                ),
                writtenAt = t + 2000,
            ),
            topicCount = 1,
        )),
        topics = listOf(TopicView(
            Script(
                id = "s1",
                sourceArticleId = "a_n1",
                sourceTitle = "忘词的那三秒",
                angle = "从忘词那三秒讲起",
                sections = listOf(
                    Section(
                        title = "开场钩子",
                        seconds = 8,
                        phrases = listOf("我准备了整整两周", "可我一上台就"),
                        cues = listOf(Cue("停顿", "说完第一句停一秒")),
                    )
                ),
                createdAt = t + 3000,
                shotAt = t + 4000,
            )
        )),
    )

    @Test
    fun `导出的内容包含这条记录的全部痕迹`() {
        val md = MarkdownExporter.note(fullLibrary(), "n1")

        assertTrue("原始记录", md.contains("# 上台为什么紧张"))
        assertTrue("正文", md.contains("我提前背了稿子，上台全忘了。"))

        assertTrue("追问章节", md.contains("## 追问"))
        assertTrue("问题原文", md.contains("背下来的那些字，是卡在嘴边，还是压根没想起来？"))
        assertTrue("回答原文", md.contains("没想起来。"))

        assertTrue("文章章节", md.contains("## 文章：忘词的那三秒"))
        assertTrue("文章正文", md.contains("熟练度错觉让人误以为自己准备好了。"))

        assertTrue("发芽章节", md.contains("## 发芽"))
        assertTrue("发芽标题", md.contains("背下来的东西不算会"))
        assertTrue("发芽的种子", md.contains("你说的：我提前背了稿子"))
        assertTrue("发芽的洞察", md.contains("大脑会把它当成已经完成的事"))

        assertTrue("选题章节", md.contains("## 选题"))
        assertTrue("角度", md.contains("从忘词那三秒讲起"))
        assertTrue("意群短语", md.contains("我准备了整整两周"))
        assertTrue("镜头提示", md.contains("停顿：说完第一句停一秒"))
        assertTrue("已拍标记", md.contains("（已拍）"))
    }

    @Test
    fun `未回答的轮次不导出`() {
        val md = MarkdownExporter.note(fullLibrary(), "n1")
        assertFalse(
            "只问没答的那一轮，答案区不该凭空多出一段空白章节",
            md.contains("是一回事吗"),
        )
    }

    @Test
    fun `AI 补充的段落会被标注出来`() {
        val md = MarkdownExporter.note(fullLibrary(), "n1")
        assertTrue(
            "用户要判断哪句是自己想的，这条信息不能丢",
            md.contains("来源标注") && md.contains("第 2 段"),
        )
        // 正文本身保持干净，标注不插在段落里——
        // 用户拿这份 md 是要二次编辑的
        assertTrue(md.contains("熟练度错觉让人误以为自己准备好了。\n"))
    }

    @Test
    fun `没有出处的发芽不硬凑出处行`() {
        val md = MarkdownExporter.note(fullLibrary(), "n1")
        // 这一节的 source 是空的，导出里不该出现孤零零的破折号
        assertFalse(
            "没有出处就别画「——」那一行",
            md.lines().any { it.trim() == "——" },
        )
    }

    @Test
    fun `空记录也能导出，不崩`() {
        val bare = Library(
            notes = listOf(
                NoteView(
                    note = Note(
                        id = "n2",
                        title = "",
                        body = "",
                        createdAt = t,
                        source = NoteSource.QUICK,
                    ),
                    deepening = null,
                )
            )
        )
        val md = MarkdownExporter.note(bare, "n2")
        assertTrue("无标题也要有个占位", md.contains("# （无标题）"))
        assertFalse(md.contains("## 追问"))
        assertFalse(md.contains("## 文章"))
    }

    @Test
    fun `导出全部会把多条记录排在同一个文档里`() {
        val md = MarkdownExporter.all(fullLibrary())
        assertTrue(md.contains("全部分享"))
        assertTrue("记录有一个一级标题", md.split("# 上台为什么紧张").size == 2)
        assertTrue("文章也在同一份文档里", md.contains("## 文章：忘词的那三秒"))
    }

    @Test
    fun `文件名去掉路径分隔符`() {
        val name = MarkdownExporter.fileName("上台/紧张：为什么?")
        assertFalse("斜杠会让文件写入失败", name.contains("/"))
        assertFalse(name.contains("："))
        assertTrue(name.endsWith(".md"))
    }

    @Test
    fun `空标题也能得到一个合法文件名`() {
        val name = MarkdownExporter.fileName("   ")
        assertTrue(name.startsWith("随手记"))
        assertTrue(name.endsWith(".md"))
    }

    @Test
    fun `标题以句号结尾时不留空点`() {
        // 英文标题常以句号结尾，`.trim()` 不管它，
        // 直接拼出来是 `meaning.-20261021.md`——这个点在 Windows 上非法
        val name = MarkdownExporter.fileName("Everyone needs a sense of meaning.")
        assertFalse("点后面不该紧跟分隔符", name.contains(".-"))
        assertTrue(name.startsWith("Everyone needs a sense of meaning-"))
    }
}
