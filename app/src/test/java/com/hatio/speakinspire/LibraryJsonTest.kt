package com.hatio.speakinspire

import com.hatio.speakinspire.data.export.LibraryJson
import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Cue
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.Material
import com.hatio.speakinspire.data.model.MaterialKind
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `data.json` 的编解码。
 *
 * ## 为什么编解码要一起测
 *
 * 这一版只导出，导入留到下一版。也就是说 **导出文件在用户的手机里躺着**，
 * 要等到下一次更新才有人读它。如果那时候才发现读不出来，
 * 用户手里那份「备份」已经废了，而且**没有第二份**。
 *
 * 所以编码和解码现在就得一起写、一起测通。
 * 每一个往返断言都在回答同一个问题：
 * 「半年后拿这个文件能把今天的内容原样装回去吗？」
 *
 * ## 重点在转义
 *
 * 用户原话里出现引号、换行、逗号、方括号都是常态。
 * 这些字符只要有一个没转义对，整个文件就是非法 JSON——
 * 而它不是「显示得难看一点」，是**一条都读不出来**。
 */
class LibraryJsonTest {

    private val t = 1_700_000_000_000L

    /**
     * 刻意塞满会让 JSON 出问题的字符：
     * 引号、逗号、方括号、换行、反斜杠、制表符。
     */
    private val nasty = "他说\"这样就行\"，然后[停顿]，换行\n和\\反斜杠\t制表符"

    private fun library() = Library(
        notes = listOf(
            NoteView(
                note = Note(
                    id = "n1",
                    title = "上台\"为什么\"紧张",
                    body = nasty,
                    createdAt = t,
                    source = NoteSource.READING,
                    parentId = "n0",
                ),
                deepening = Deepening(
                    id = "d_n1",
                    noteId = "n1",
                    rounds = listOf(
                        Round("背下来的那些字，是卡在嘴边，还是压根没想起来？", "没想起来，一片空白。"),
                        Round("「没想起来」和\"忘了\"是一回事吗？", null),
                    ),
                    startedAt = t,
                    finishedAt = t + 1_000,
                ),
                sprouts = listOf(
                    Sprout(
                        id = "sp1",
                        noteId = "n1",
                        title = "背下来的东西不算会",
                        sections = listOf(
                            SproutSection(
                                seed = "我提前背了稿子, 上台全忘了",
                                insight = "把话写死之后，大脑会把它当成[已经完成]的事。",
                                source = "",
                            )
                        ),
                        createdAt = t + 5_000,
                    )
                ),
                articleId = "a_n1",
            ),
        ),
        articles = listOf(
            ArticleView(
                article = Article(
                    id = "a_n1",
                    sourceNoteId = "n1",
                    // 快照里也要有非法 JSON 的字符，它是最容易被忽略的一段
                    sourceText = nasty,
                    title = "忘词的那三秒",
                    paragraphs = listOf(
                        Paragraph("我提前背了稿子。", mine = true),
                        Paragraph("熟练度错觉让人误以为\"准备好了\"。", mine = false),
                    ),
                    writtenAt = t + 2_000,
                ),
                topicCount = 1,
            )
        ),
        topics = listOf(
            TopicView(
                Script(
                    id = "s1",
                    sourceArticleId = "a_n1",
                    sourceTitle = "忘词的那三秒",
                    angle = "从忘词\"那三秒\"讲起",
                    sections = listOf(
                        Section(
                            title = "开场钩子",
                            seconds = 8,
                            phrases = listOf("我准备了整整两周", "可我一上台就…"),
                            cues = listOf(Cue("停顿", "说完第一句停一秒")),
                        )
                    ),
                    createdAt = t + 3_000,
                    shotAt = t + 4_000,
                    parked = false,
                    note = "先在家里录一遍",
                )
            )
        ),
    )

    private fun encoded(lib: Library = library()) =
        LibraryJson.encode(lib, appVersion = "0.10.0", exportedAt = t)

    // ---------- 结构 ----------

    @Test
    fun `文件头带 schemaVersion`() {
        val json = encoded()
        assertTrue(
            "没有 schemaVersion 就没法判断能不能读",
            "\"schemaVersion\":${LibraryJson.SCHEMA_VERSION}" in json,
        )
    }

    @Test
    fun `schemaVersion 必须跟数据库版本一致`() {
        // 迁移了表结构却忘了提这个数，导入时就会用旧解析器读新结构，
        // 最坏的结果是静默丢掉用户没见过的字段。
        //
        // 读源码而不是反射：Room 的 @Database 是 CLASS 保留级别的注解，
        // 运行时 `getAnnotation` 拿到的是 null（试过，NPE）。
        // 这也和 DaoWriteStrategyTest 的做法一致——有些约束只能在文本层面守
        val src = File(sourceRoot(), "app/src/main/java/com/hatio/speakinspire/" +
            "data/local/SpeakInspireDatabase.kt").readText()
        val dbVersion = Regex("""version\s*=\s*(\d+)""")
            .find(src)?.groupValues?.get(1)?.toInt()
        assertEquals(
            "两个版本号必须同步：迁了表结构却没提 schemaVersion，" +
                "导入时就会用旧解析器读新结构",
            LibraryJson.SCHEMA_VERSION, dbVersion,
        )
        assertEquals("数据库升版时这里也要跟着提", dbVersion, LibraryJson.SCHEMA_VERSION)
    }

    /** 从测试进程所在目录往上找仓库根 */
    private fun sourceRoot(): File {
        var dir: File = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            if (File(dir, "app/src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }

    @Test
    fun `导出的文件名固定`() {
        assertEquals("data.json", LibraryJson.FILE_NAME)
    }

    // ---------- 往返 ----------

    @Test
    fun `记录能原样读回来`() {
        val decoded = LibraryJson.decode(encoded())!!
        assertEquals(1, decoded.notes.size)
        val n = decoded.notes.first()
        assertEquals("n1", n.id)
        assertEquals("上台\"为什么\"紧张", n.title)
        assertEquals("正文里的引号和换行必须一个字不差", nasty, n.body)
        assertEquals(NoteSource.READING, n.source)
        assertEquals("n0", n.parentId)
        assertEquals(t, n.createdAt)
    }

    @Test
    fun `追问能原样读回来`() {
        val d = LibraryJson.decode(encoded())!!.deepenings.single()
        assertEquals("d_n1", d.id)
        assertEquals("n1", d.noteId)
        assertEquals(2, d.rounds.size)
        assertEquals("没想起来，一片空白。", d.rounds[0].answer)
        // 没答的那一轮要还是「没答」，不能变成空字符串——
        // 界面靠它区分「问过了等你答」和「答了一个空」
        assertNull(d.rounds[1].answer)
        assertEquals(t + 1_000, d.finishedAt)
    }

    @Test
    fun `发芽能原样读回来`() {
        val sp = LibraryJson.decode(encoded())!!.sprouts.single()
        assertEquals("sp1", sp.id)
        assertEquals("n1", sp.noteId)
        assertEquals("我提前背了稿子, 上台全忘了", sp.sections.single().seed)
        // 逗号在 seed 里。切分要是按逗号走的，这半句就没了
        assertTrue(sp.sections.single().insight.contains("[已经完成]"))
        assertTrue("没出处就该是空串", sp.sections.single().source.isEmpty())
    }

    @Test
    fun `文章与段落来源标记能原样读回来`() {
        val a = LibraryJson.decode(encoded())!!.articles.single()
        assertEquals("a_n1", a.id)
        assertEquals("n1", a.sourceNoteId)
        assertEquals(nasty, a.sourceText)
        assertEquals(2, a.paragraphs.size)
        assertTrue("这段是我说的", a.paragraphs[0].mine)
        assertEquals("这段是 AI 补的", false, a.paragraphs[1].mine)
    }

    @Test
    fun `选题骨架能原样读回来`() {
        val s = LibraryJson.decode(encoded())!!.topics.single()
        assertEquals("s1", s.id)
        assertEquals("从忘词\"那三秒\"讲起", s.angle)
        assertEquals(t + 4_000, s.shotAt)
        val sec = s.sections.single()
        assertEquals("开场钩子", sec.title)
        assertEquals(8, sec.seconds)
        assertEquals(listOf("我准备了整整两周", "可我一上台就…"), sec.phrases)
        assertEquals("停顿", sec.cues.single().icon)
    }

    @Test
    fun `空库也能导出和读回`() {
        val json = encoded(Library())
        val decoded = LibraryJson.decode(json)!!
        assertTrue(decoded.isEmpty)
        assertEquals(0, decoded.notes.size)
    }

    // ---------- 容错 ----------

    @Test
    fun `不是随手记的文件返回 null`() {
        assertNull(LibraryJson.decode("{\"hello\":1}"))
        assertNull(LibraryJson.decode("完全不是 json"))
    }

    @Test
    fun `单条坏掉不拖累其他条`() {
        // 模拟文件被人手改过：第二条记录丢了 id
        val good = encoded()
        val broken = good.replaceFirst("\"id\":\"n1\"", "\"id\":\"\"")
        val decoded = LibraryJson.decode(broken)
        // 整体仍然解析成功（header 还在），坏的那条被跳过
        assertEquals(LibraryJson.SCHEMA_VERSION, decoded!!.schemaVersion)
        assertEquals(0, decoded.notes.size)
        // 但文章和选题不在这条记录里，它们照常读出来
        assertEquals(1, decoded.articles.size)
        assertEquals(1, decoded.topics.size)
    }

    @Test
    fun `未知的 source 退回随手记而不是丢掉整条`() {
        val json = encoded().replace("\"source\":\"READING\"", "\"source\":\"FROM_MARS\"")
        val n = LibraryJson.decode(json)!!.notes.single()
        assertEquals(NoteSource.QUICK, n.source)
    }

    // ---------- 可读性 ----------

    @Test
    fun `顶层按字段分行，用户点开不像坏文件`() {
        val json = encoded()
        assertTrue("第一行得是个孤零零的 {", json.startsWith("{\n"))
        assertTrue("字段之间要换行", "\n  \"app\"" in json)
        assertTrue("结尾要闭合", json.trimEnd().endsWith("}"))
    }

    @Test
    fun `三个数组的 key 不和计数重名`() {
        // "counts" 里要是也有 "notes"，解析器取数组时会先撞上那个数字，
        // 表现为「导出的文件里一条记录都没有」
        val json = encoded()
        val countsBlock = json.substringAfter("\"counts\":").substringBefore("}")
        assertTrue("counts 里不能出现 notes 这个词", "\"notes\"" !in countsBlock)
        assertTrue("counts 里不能出现 articles 这个词", "\"articles\"" !in countsBlock)
    }

    // ---------- v5：素材与风格 ----------

    /** 只用 v5 新增字段的一份小库 */
    private fun v5Library() = Library(
        notes = emptyList(),
        articles = listOf(
            ArticleView(
                article = Article(
                    id = "a_v5",
                    sourceNoteId = null,
                    title = "卡壳的那三秒",
                    paragraphs = listOf(
                        Paragraph("我提前准备过。", mine = true),
                        Paragraph("熟练度错觉让人误以为准备好了。", mine = false),
                        // 第三档：芽里别人的话
                        Paragraph("海明威说过，勇气是压力下的优雅。", mine = false, fromSprout = true),
                    ),
                    writtenAt = t,
                    styleId = "spoken",
                ),
            )
        ),
        topics = listOf(
            TopicView(
                Script(
                    id = "s_v5",
                    sourceArticleId = "a_v5",
                    sourceTitle = "卡壳的那三秒",
                    angle = "从卡壳讲起",
                    sections = listOf(Section("开场", 8, listOf("我准备了两周"))),
                    createdAt = t,
                    materials = listOf(
                        Material(MaterialKind.EXAMPLE, "上次跟客户吃饭也是", ""),
                        Material(MaterialKind.QUOTE, "勇气是压力下的优雅", "海明威"),
                    ),
                )
            )
        ),
    )

    @Test
    fun `素材能原样读回来`() {
        val topic = LibraryJson.decode(LibraryJson.encode(v5Library(), "0.11.0", t))!!
            .topics.single()
        assertEquals(2, topic.materials.size)
        assertEquals(MaterialKind.EXAMPLE, topic.materials[0].kind)
        assertEquals("上次跟客户吃饭也是", topic.materials[0].text)
        assertEquals("", topic.materials[0].source)
        assertEquals("海明威", topic.materials[1].source)
    }

    @Test
    fun `风格能原样读回来`() {
        // 用户看到的「这是篇口播稿」和文件里写的必须是同一件事
        val article = LibraryJson.decode(LibraryJson.encode(v5Library(), "0.11.0", t))!!
            .articles.single()
        assertEquals("spoken", article.styleId)
    }

    @Test
    fun `来源三档都能原样读回来`() {
        val a = LibraryJson.decode(LibraryJson.encode(v5Library(), "0.11.0", t))!!.articles.single()
        assertEquals(3, a.paragraphs.size)
        assertEquals("你说的", a.paragraphs[0].sourceLabel)
        assertEquals("AI 补", a.paragraphs[1].sourceLabel)
        assertEquals("AI 补 · 含他人观点", a.paragraphs[2].sourceLabel)
    }

    @Test
    fun `v4 的文件里没有素材和风格也能读`() {
        // 用户手机里可能躺着旧版本导出的文件。读的时候不能崩，
        // 也不能编一个默认风格出来——那时候还没有风格这回事
        val old = """
            {
              "schemaVersion": 4,
              "articles": [
                {"id":"a1","title":"旧文章","sourceNoteId":null,"sourceText":"",
                 "paragraphs":[{"t":"我说的","m":true}],"writtenAt":1,"edited":false}
              ],
              "topics": [
                {"id":"s1","sourceArticleId":"a1","sourceTitle":"旧文章","angle":"角度",
                 "sections":[],"createdAt":1,"shotAt":null,"parked":false,"note":""}
              ],
              "notes": []
            }
        """.trimIndent()
        val decoded = LibraryJson.decode(old)!!
        assertEquals(4, decoded.schemaVersion)
        assertEquals("", decoded.articles.single().styleId)
        assertTrue(decoded.topics.single().materials.isEmpty())
        assertEquals("旧段落一律不是发芽来的", false, decoded.articles.single().paragraphs[0].fromSprout)
    }
}
