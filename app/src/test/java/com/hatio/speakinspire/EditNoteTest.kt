package com.hatio.speakinspire

import com.hatio.speakinspire.data.local.ArticleEntity
import com.hatio.speakinspire.data.local.NoteEntity
import com.hatio.speakinspire.data.repository.IdeaRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑记录的行为约束。
 *
 * 全部来自真机实测踩到的坑，不是设计偏好。
 */
class EditNoteTest {

    private val noteDao = FakeNoteDao()
    private val deepeningDao = FakeDeepeningDao()
    private val articleDao = FakeArticleDao()
    private val scriptDao = FakeScriptDao()
    private val repo = IdeaRepository(noteDao, deepeningDao, articleDao, scriptDao, FakeSproutDao(), FakeTrashedDao())

    private val t = 1_700_000_000_000L

    @Test
    fun `编辑后内容真的变了`() = runBlocking {
        // bug：noteId 从没写进 EditNoteUiState，save() 拿到空字符串，
        // 更新的是空 id —— 表现为「改了、保存了、没变化」。
        noteDao.seed(listOf(seedNote("n1", "旧标题", "旧正文")))

        val ok = repo.updateNote("n1", "新标题", "新正文")

        assertTrue("应该更新成功", ok)
        assertEquals("新标题", noteDao.findById("n1")!!.title)
        assertEquals("新正文", noteDao.findById("n1")!!.body)
    }

    @Test
    fun `编辑不改变创建时间`() = runBlocking {
        // createdAt 变了会让这条记录在时间线上跳到最前面，
        // 而用户只是想改个字，不是在新建一条。
        noteDao.seed(listOf(seedNote("n1", "标题", "正文", createdAt = 1000L)))

        repo.updateNote("n1", "改过的标题", "正文")

        assertEquals("时间不该动", 1000L, noteDao.findById("n1")!!.createdAt)
    }

    @Test
    fun `更新不存在的记录返回 false 而不是静默成功`() = runBlocking {
        // 静默 return 是最难查的失败：没报错、没异常，
        // 界面上看起来一切正常，内容却没变。
        assertFalse(
            "找不到记录时必须明确返回 false，调用方才能报错",
            repo.updateNote("不存在", "标题", "正文"),
        )
    }

    @Test
    fun `空 id 不会误改任何记录`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1", "标题", "正文")))

        assertFalse(repo.updateNote("", "新标题", "新正文"))
        assertEquals("原记录不该被动到", "标题", noteDao.findById("n1")!!.title)
    }

    @Test
    fun `编辑后详情页能读到新内容`() = runBlocking {
        // 端到端：改完再用 findIdea 读一次，
        // 确认详情页显示的是新内容而不是缓存。
        noteDao.seed(listOf(seedNote("n1", "旧标题", "旧正文")))

        repo.updateNote("n1", "新标题", "新正文")
        val idea = repo.findIdea("n1")

        assertEquals("新标题", idea!!.note.title)
        assertEquals("新正文", idea.note.body)
    }

    private fun seedNote(
        id: String,
        title: String,
        body: String,
        createdAt: Long = t,
    ) = NoteEntity(
        id = id,
        title = title,
        body = body,
        createdAt = createdAt,
        source = "QUICK",
        parentId = null,
    )
}
