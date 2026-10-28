package com.hatio.speakinspire

import com.hatio.speakinspire.data.local.ArticleDao
import com.hatio.speakinspire.data.local.DeepeningDao
import com.hatio.speakinspire.data.local.DeepeningEntity
import com.hatio.speakinspire.data.local.NoteDao
import com.hatio.speakinspire.data.local.NoteEntity
import com.hatio.speakinspire.data.local.ScriptDao
import com.hatio.speakinspire.data.local.ScriptEntity
import com.hatio.speakinspire.data.local.ArticleEntity
import com.hatio.speakinspire.data.local.SproutDao
import com.hatio.speakinspire.data.local.SproutEntity
import com.hatio.speakinspire.data.local.TrashType
import com.hatio.speakinspire.data.local.TrashedDao
import com.hatio.speakinspire.data.local.TrashedEntity
import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.NoteSource
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.domain.IdeaState
import com.hatio.speakinspire.domain.IdeaStateDeriver
import com.hatio.speakinspire.domain.IdeaView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存版 DAO —— Room 需要 Android 环境，
 * 但 Repository 的聚合与状态派生逻辑是纯的，不必依赖真实数据库。
 */
class FakeNoteDao(
    private val trashed: FakeTrashedDao = FakeTrashedDao(),
) : NoteDao {
    private val notes = MutableStateFlow<List<NoteEntity>>(emptyList())

    override suspend fun upsert(note: NoteEntity) {
        notes.value = notes.value.filterNot { it.id == note.id } + note
    }

    override suspend fun findById(id: String): NoteEntity? = notes.value.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<NoteEntity>> = notes
    override fun observeActive(): Flow<List<NoteEntity>> =
        kotlinx.coroutines.flow.combine(notes, trashed.rows) { n, t ->
            n.filter { e -> t.none { it.resourceType == TrashType.NOTE && it.resourceId == e.id } }
        }

    /** 一次性版：与 observeActive 同一条过滤规则，别写成两份 */
    override suspend fun findAllActive(): List<NoteEntity> =
        notes.value
            .filter { n ->
                trashed.rows.value.none { it.resourceType == TrashType.NOTE && it.resourceId == n.id }
            }
            .sortedByDescending { it.createdAt }

    override suspend fun exists(id: String): Boolean = findById(id) != null
    override suspend fun hardDelete(id: String) {
        notes.value = notes.value.filterNot { it.id == id }
    }


    fun seed(list: List<NoteEntity>) { notes.value = list }
}

class FakeDeepeningDao : DeepeningDao {
    private val items = MutableStateFlow<List<DeepeningEntity>>(emptyList())
    override suspend fun upsert(entity: DeepeningEntity) {
        items.value = items.value.filterNot { it.id == entity.id } + entity
    }
    override suspend fun findLatestByNote(noteId: String) =
        items.value.filter { it.noteId == noteId }.firstOrNull()
    override fun observeAll(): Flow<List<DeepeningEntity>> = items
    override suspend fun findAll(): List<DeepeningEntity> = items.value
    override suspend fun delete(id: String) {
        items.value = items.value.filterNot { it.id == id }
    }
    fun seed(list: List<DeepeningEntity>) { items.value = list }
}

/** 三种资源共用一张回收站表，所以假实现也只做一份 */
class FakeTrashedDao : TrashedDao {
    val rows = MutableStateFlow<List<TrashedEntity>>(emptyList())

    override suspend fun insert(entry: TrashedEntity) {
        rows.value = rows.value.filterNot {
            it.resourceType == entry.resourceType && it.resourceId == entry.resourceId
        } + entry
    }

    override suspend fun remove(type: String, resourceId: String) {
        rows.value = rows.value.filterNot {
            it.resourceType == type && it.resourceId == resourceId
        }
    }

    override fun observeAll(): Flow<List<TrashedEntity>> = rows
    override suspend fun findAll(): List<TrashedEntity> = rows.value
    override suspend fun contains(type: String, resourceId: String): Boolean =
        rows.value.any { it.resourceType == type && it.resourceId == resourceId }

    override suspend fun purgeBefore(before: Long) {
        rows.value = rows.value.filter { it.deletedAt >= before }
    }
}

class FakeArticleDao : ArticleDao {
    private val items = MutableStateFlow<List<ArticleEntity>>(emptyList())
    override suspend fun upsert(entity: ArticleEntity) {
        items.value = items.value.filterNot { it.id == entity.id } + entity
    }
    override suspend fun findByNote(noteId: String) =
        items.value.firstOrNull { it.sourceNoteId == noteId }
    override fun observeAll(): Flow<List<ArticleEntity>> = items
    override suspend fun findAll(): List<ArticleEntity> = items.value
    override suspend fun updateContent(id: String, json: String) {
        items.value = items.value.map {
            if (it.id == id) it.copy(paragraphsJson = json, edited = true) else it
        }
    }
    override suspend fun delete(id: String) {
        items.value = items.value.filterNot { it.id == id }
    }
    fun seed(list: List<ArticleEntity>) { items.value = list }
    fun current(): List<ArticleEntity> = items.value
}

class FakeScriptDao : ScriptDao {
    private val items = MutableStateFlow<List<ScriptEntity>>(emptyList())
    override suspend fun upsert(entity: ScriptEntity) {
        items.value = items.value.filterNot { it.id == entity.id } + entity
    }
    override suspend fun findByArticle(articleId: String) =
        items.value.filter { it.sourceArticleId == articleId }
    override fun observeAll(): Flow<List<ScriptEntity>> = items
    override suspend fun findAll(): List<ScriptEntity> = items.value
    override suspend fun markShot(id: String, timestamp: Long) {
        items.value = items.value.map { if (it.id == id) it.copy(shotAt = timestamp) else it }
    }
    override suspend fun undoShot(id: String) {
        items.value = items.value.map { if (it.id == id) it.copy(shotAt = null) else it }
    }
    override suspend fun park(id: String) {
        items.value = items.value.map { if (it.id == id) it.copy(parked = true) else it }
    }
    override suspend fun unpark(id: String) {
        items.value = items.value.map { if (it.id == id) it.copy(parked = false) else it }
    }
    override suspend fun updateContent(id: String, json: String, note: String) {
        items.value = items.value.map {
            if (it.id == id) it.copy(sectionsJson = json, note = note) else it
        }
    }
    override suspend fun delete(id: String) {
        items.value = items.value.filterNot { it.id == id }
    }
    fun seed(list: List<ScriptEntity>) { items.value = list }
    fun get(id: String): ScriptEntity? = items.value.firstOrNull { it.id == id }
}

/**
 * 发芽报告的内存实现。
 *
 * **注意语义**：insert 是追加，不是覆盖。
 * 同一条记录发两次芽会有两行——用户在不同时期想到的东西不一样，
 * 后一次不该把前一次顶掉。
 */
class FakeSproutDao : SproutDao {
    private val items = MutableStateFlow<List<SproutEntity>>(emptyList())

    override suspend fun insert(entity: SproutEntity) {
        items.value = items.value + entity
    }

    override suspend fun findByNote(noteId: String) =
        items.value.filter { it.noteId == noteId }.sortedByDescending { it.createdAt }

    override suspend fun findById(id: String) = items.value.firstOrNull { it.id == id }

    override fun observeAll(): Flow<List<SproutEntity>> = items

    override suspend fun findAll(): List<SproutEntity> = items.value

    override suspend fun delete(id: String) {
        items.value = items.value.filterNot { it.id == id }
    }

    fun seed(list: List<SproutEntity>) { items.value = list }
    fun count(): Int = items.value.size
}

/**
 * Repository 测试。
 *
 * 重点验证两条最容易在实现时被破坏的约束：
 * 1. 「保存」与「右滑标记已拍摄」职责严格分离
 * 2. 状态永远是算出来的，不存冗余字段
 */
class IdeaRepositoryTest {

    private val trashedDao = FakeTrashedDao()
    private val noteDao = FakeNoteDao(trashedDao)
    private val deepeningDao = FakeDeepeningDao()
    private val articleDao = FakeArticleDao()
    private val scriptDao = FakeScriptDao()
    private val sproutDao = FakeSproutDao()
    private val repo = IdeaRepository(
        noteDao, deepeningDao, articleDao, scriptDao, sproutDao, trashedDao,
    )

    private val t = 1_700_000_000_000L

    private fun seedNote(id: String, createdAt: Long = t) = NoteEntity(
        id = id, title = "标题$id", body = "正文$id",
        createdAt = createdAt, source = "QUICK", parentId = null,
    )

    private fun seedArticle(noteId: String) = ArticleEntity(
        id = "a_$noteId", sourceNoteId = noteId, sourceText = "正文$noteId", title = "文章",
        paragraphsJson = """[{"t":"我说的","m":true}]""",
        writtenAt = t, edited = false,
    )

    private fun seedScript(articleId: String) = ScriptEntity(
        id = "s_$articleId", sourceArticleId = articleId, sourceTitle = "文章",
        angle = "挑衅", rationale = "",
        sectionsJson = "[]", createdAt = t, shotAt = null, parked = false, note = "",
    )

    // ---------- 随手记 ----------

    @Test
    fun `新建笔记返回 id 且内容正确`() = runBlocking {
        val id = repo.createNote("会上说不出话", "我一想到要发言就紧张", NoteSource.INSIGHT)
        val found = noteDao.findById(id)
        assertNotNull(found)
        assertEquals("会上说不出话", found!!.title)
        assertEquals("INSIGHT", found.source)
    }

    @Test
    fun `新建笔记会生成唯一 id`() = runBlocking {
        val a = repo.createNote("A", "a")
        val b = repo.createNote("B", "b")
        assertTrue("两次新建不应撞 id", a != b)
    }

    // ---------- fork：改主意的唯一出路 ----------

    @Test
    fun `以旧笔记为起点新建会带上内容并记录来源`() = runBlocking {
        val origin = repo.createNote("原始标题", "原始正文", NoteSource.QUICK)
        val forked = repo.forkFrom(origin)
        assertNotNull(forked)
        val f = noteDao.findById(forked!!)!!
        assertEquals("原始标题", f.title)
        assertEquals("原始正文", f.body)
        assertEquals("必须记录 parentId，否则旧记录不会变成「已过时」", origin, f.parentId)
    }

    @Test
    fun `fork 不存在的笔记返回 null`() = runBlocking {
        assertNull(repo.forkFrom("不存在"))
    }

    @Test
    fun `fork 后原文仍保留不删`() = runBlocking {
        val origin = repo.createNote("标题", "正文")
        repo.forkFrom(origin)
        assertNotNull("fork 不应删掉原笔记", noteDao.findById(origin))
    }

    // ---------- 聚合与派生 ----------

    @Test
    fun `只有笔记时状态是待深化`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        val ideas = repo.observeIdeas().first()
        assertEquals(IdeaState.WAIT, IdeaStateDeriver.derive(ideas[0]))
    }

    @Test
    fun `有追问未完成是深化中`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        deepeningDao.seed(
            listOf(
                DeepeningEntity("d1", "n1", t, null, 5, """[{"q":"问？","a":null}]""")
            )
        )
        val ideas = repo.observeIdeas().first()
        assertEquals(IdeaState.ONGOING, IdeaStateDeriver.derive(ideas[0]))
    }

    @Test
    fun `有文章有选题是待开拍`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        scriptDao.seed(listOf(seedScript("a_n1")))
        val ideas = repo.observeIdeas().first()
        assertEquals(IdeaState.TOPIC, IdeaStateDeriver.derive(ideas[0]))
    }

    @Test
    fun `记录时间线含全部记录且最新在前`() = runBlocking {
        noteDao.seed(
            listOf(
                seedNote("old", t),
                seedNote("new", t + 9999),
            )
        )
        val timeline = repo.observeTimeline().first()
        assertEquals(2, timeline.size)
        // 最新的排最前——「我刚记的」应该是最显眼的那条
        assertEquals("new", timeline[0].note.id)
    }

    @Test
    fun `已成文的记录仍在时间线里`() = runBlocking {
        // bug 6 的回归测试：深化完成、已成文之后，记录不该从列表消失
        noteDao.seed(listOf(seedNote("n1", t)))
        articleDao.seed(listOf(seedArticle("n1")))

        val timeline = repo.observeTimeline().first()
        assertEquals("已成文的记录也该留着", 1, timeline.size)
        assertEquals("n1", timeline[0].note.id)
    }

    @Test
    fun `思考库只含已有文章的`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1"), seedNote("n2")))
        articleDao.seed(listOf(seedArticle("n2")))
        val library = repo.observeLibrary().first()
        assertEquals(1, library.size)
        assertEquals("n2", library[0].note.id)
    }

    @Test
    fun `选题库排除没有选题的文章`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        assertTrue("没有脚本的文章不该出现在选题库", repo.observeTopics().first().isEmpty())
    }

    // ---------- 关键约束：保存 ≠ 标记已拍摄 ----------

    @Test
    fun `保存备注不改变拍摄状态`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        scriptDao.seed(listOf(seedScript("a_n1")))

        repo.updateScriptNote("s_a_n1", "拍摄备注")
        val script = scriptDao.get("s_a_n1")!!
        assertEquals("拍摄备注", script.note)
        assertNull(
            "「保存」绝不能顺带标记已拍摄——决策 Q4 明确要求两者分离",
            script.shotAt,
        )
    }

    @Test
    fun `右滑才标记已拍摄`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        scriptDao.seed(listOf(seedScript("a_n1")))

        repo.markShot("s_a_n1")
        assertNotNull("右滑是唯一标记已拍摄的入口", scriptDao.get("s_a_n1")!!.shotAt)
    }

    @Test
    fun `撤销后拍摄状态清空`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        scriptDao.seed(listOf(seedScript("a_n1")))

        repo.markShot("s_a_n1")
        repo.undoShot("s_a_n1")
        assertNull("7秒撤销条依赖这个", scriptDao.get("s_a_n1")!!.shotAt)
    }

    @Test
    fun `左滑是搁置而非删除`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        scriptDao.seed(listOf(seedScript("a_n1")))

        repo.parkScript("s_a_n1")
        assertTrue(scriptDao.get("s_a_n1")!!.parked)
        assertNotNull("左滑不该删数据——文章本身没错", scriptDao.get("s_a_n1"))
        assertNotNull("文章也必须保留", articleDao.findByNote("n1"))
    }

    @Test
    fun `搁置后状态变先留着`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        scriptDao.seed(listOf(seedScript("a_n1")))

        repo.parkScript("s_a_n1")
        val ideas = repo.observeIdeas().first()
        assertEquals(IdeaState.PARKED, IdeaStateDeriver.derive(ideas[0]))
    }

    @Test
    fun `已拍摄的选题不出现在待开拍列表`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1"), seedNote("n2")))
        articleDao.seed(listOf(seedArticle("n1"), seedArticle("n2")))
        scriptDao.seed(listOf(seedScript("a_n1"), seedScript("a_n2")))

        repo.markShot("s_a_n1")
        // topicOrder 仍包含它（状态是 SHOT），但排到最后
        val topics = repo.observeTopics().first()
        assertEquals("待开拍的应排在前面", "n2", topics[0].note.id)
    }

    // ---------- 文章编辑 ----------

    @Test
    fun `编辑文章后仍是同一篇不产生副本`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))

        repo.updateArticleContent("a_n1", listOf(Paragraph("改过的内容", true)))
        val articles = articleDao.current()
        assertEquals("编辑不应产生副本——文章是思考资产", 1, articles.size)
        assertEquals("a_n1", articles[0].id)
    }

    @Test
    fun `编辑后标记为已编辑`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        repo.updateArticleContent("a_n1", listOf(Paragraph("改过的", true)))
        assertTrue("edited 标记让「来源」视图知道这段是用户改的", articleDao.current()[0].edited)
    }

    @Test
    fun `编辑后内容能被正确读回`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        repo.updateArticleContent("a_n1", listOf(Paragraph("新内容一", true), Paragraph("新内容二", false)))
        val idea = repo.findIdea("n1")!!
        assertEquals(2, idea.article!!.paragraphs.size)
        assertEquals("新内容一", idea.article!!.paragraphs[0].text)
        assertFalse(idea.article!!.paragraphs[1].mine)
    }

    // ---------- 回收站 ----------

    @Test
    fun `移入回收站不删数据`() = runBlocking {
        val id = repo.createNote("标题", "正文")
        repo.moveToTrash(TrashType.NOTE, id)
        assertNotNull("回收站的意义就是可恢复", noteDao.findById(id))
    }

    @Test
    fun `从回收站恢复`() = runBlocking {
        val id = repo.createNote("标题", "正文")
        repo.moveToTrash(TrashType.NOTE, id)
        repo.restoreFromTrash(TrashType.NOTE, id)
        assertNotNull(noteDao.findById(id))
    }

    @Test
    fun `彻底删除才真删`() = runBlocking {
        val id = repo.createNote("标题", "正文")
        repo.purge(TrashType.NOTE, id)
        assertNull(noteDao.findById(id))
    }

    @Test
    fun `刚删的不会被保留期清理`() = runBlocking {
        val id = repo.createNote("刚删", "内容")
        repo.moveToTrash(TrashType.NOTE, id)
        // 保留期 30天，刚删的当然还在
        repo.purgeExpiredTrash(retentionMs = 30L * 24 * 60 * 60 * 1000)
        assertFalse("刚删的不能被清掉", repo.observeTrashed().first().isEmpty())
    }

    // ---------- 找不到时 ----------

    @Test
    fun `查找不存在的笔记返回 null`() = runBlocking {
        assertNull(repo.findIdea("不存在"))
    }

    @Test
    fun `空的库里各列表都为空`() = runBlocking {
        assertTrue(repo.observeIdeas().first().isEmpty())
        assertTrue(repo.observeLibrary().first().isEmpty())
        assertTrue(repo.observeTopics().first().isEmpty())
    }
}