package com.hatio.speakinspire.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 三种资源的**生命周期**边界，跑在真实 SQLite 上。
 *
 * 这是 V0.10 的核心契约：
 *
 * | 删掉 | 追问 | 发芽 | 文章 | 选题 |
 * |---|---|---|---|---|
 * | 记录 | ✂️ 删 | ✂️ 删 | ✅ 留 | ✅ 留 |
 * | 文章 | — | — | ✂️ 删 | ✅ 留 |
 *
 * 左边两列是「附着物」：追问的每一轮在谈记录里那句话，发芽的种子直接引用原文，
 * 记录没了它们就失去了锚点。
 * 中间两列是「衍生资源」：它们是那次思考的产物，产物可以离开原料活下去。
 *
 * 之前所有测试都用内存假 DAO，而假 DAO **没有外键**，
 * 所以这张表里任何一格都没被真正验证过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CascadeSemanticsTest {

    private lateinit var db: SpeakInspireDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SpeakInspireDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** 一整套：记录 + 追问 + 发芽 + 文章 + 两条选题 */
    private fun seed() = runBlocking {
        db.noteDao().upsert(note("n1", "我提前背了稿子，上台全忘了"))
        db.deepeningDao().upsert(
            DeepeningEntity(
                id = "d1", noteId = "n1", startedAt = 1, finishedAt = 2,
                estimatedRounds = 5, roundsJson = "[]",
            )
        )
        db.sproutDao().insert(
            SproutEntity(id = "sp1", noteId = "n1", title = "记忆与焦虑", sectionsJson = "[]", createdAt = 3)
        )
        db.articleDao().upsert(article("a1", "n1", "背稿与临场"))
        db.scriptDao().upsert(script("s1", "a1"))
        db.scriptDao().upsert(script("s2", "a1"))
        Unit
    }

    @Test
    fun `删记录时追问和发芽跟着走`() = runBlocking {
        seed()

        db.noteDao().hardDelete("n1")

        assertEquals("追问失去了锚点，该删", 0, db.deepeningDao().findAll().size)
        assertEquals("发芽的种子原文没了，该删", 0, db.sproutDao().findAll().size)
    }

    @Test
    fun `删记录不带走文章`() = runBlocking {
        seed()

        db.noteDao().hardDelete("n1")

        val article = db.articleDao().findAll().firstOrNull()
        assertNotNull("文章是资产，不能跟着记录一起消失", article)
        assertEquals("a1", article!!.id)
    }

    @Test
    fun `删记录不带走选题`() = runBlocking {
        seed()

        db.noteDao().hardDelete("n1")

        assertEquals("选题是拆好的拍摄计划，必须留着", 2, db.scriptDao().findAll().size)
    }

    @Test
    fun `删文章不带走选题`() = runBlocking {
        seed()

        db.articleDao().delete("a1")

        assertEquals("文章没了，分镜还能拿去拍", 2, db.scriptDao().findAll().size)
        assertEquals("", 0, db.articleDao().findAll().size)
    }

    @Test
    fun `原始记录删了，文章靠快照仍能自洽`() = runBlocking {
        seed()

        db.noteDao().hardDelete("n1")

        val article = db.articleDao().findByNote("n1")
        assertNotNull(article)
        assertEquals(
            "sourceText 是生成时的原文快照，不能为空",
            "我提前背了稿子，上台全忘了",
            article!!.sourceText,
        )
        // 出处 id 还在，但记录已经查不到了——UI 据此显示「原始记录已删除」
        assertNull("记录确实没了", db.noteDao().findById("n1"))
    }

    @Test
    fun `选题脱离文章后仍有标题快照`() = runBlocking {
        seed()

        db.articleDao().delete("a1")

        val script = db.scriptDao().findAll().first()
        assertEquals("背稿与临场", script.sourceTitle)
        assertEquals("出处 id 保留，能查到就跳转，查不到就只显示标题", "a1", script.sourceArticleId)
    }

    @Test
    fun `重建同一篇文章不会波及选题`() = runBlocking {
        seed()

        // 重新成文会 upsert 同一个 id。以前用 REPLACE 时这里会把选题清空
        db.articleDao().upsert(article("a1", "n1", "改过的标题"))

        assertEquals("选题不能被一次保存抹掉", 2, db.scriptDao().findAll().size)
    }

    // ---------- 构造器 ----------

    private fun note(id: String, body: String) = NoteEntity(
        id = id, title = body.take(10), body = body,
        createdAt = 1L, source = "QUICK", parentId = null,
    )

    private fun article(id: String, noteId: String, title: String) = ArticleEntity(
        id = id, sourceNoteId = noteId, sourceText = "我提前背了稿子，上台全忘了",
        title = title, paragraphsJson = "[]", writtenAt = 2L, edited = false,
    )

    private fun script(id: String, articleId: String) = ScriptEntity(
        id = id, sourceArticleId = articleId, sourceTitle = "背稿与临场",
        angle = "角度", rationale = "", sectionsJson = "[]", createdAt = 3L,
        shotAt = null, parked = false, note = "",
    )
}
