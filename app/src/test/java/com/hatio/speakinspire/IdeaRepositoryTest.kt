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
import com.hatio.speakinspire.domain.NoteState
import com.hatio.speakinspire.domain.TopicState
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
    override suspend fun hasChild(id: String): Boolean = notes.value.any { it.parentId == id }
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

class FakeArticleDao(
    private val trashed: FakeTrashedDao = FakeTrashedDao(),
) : ArticleDao {
    private val items = MutableStateFlow<List<ArticleEntity>>(emptyList())

    private fun alive(e: ArticleEntity) =
        trashed.rows.value.none { it.resourceType == TrashType.ARTICLE && it.resourceId == e.id }

    override suspend fun upsert(entity: ArticleEntity) {
        items.value = items.value.filterNot { it.id == entity.id } + entity
    }
    override suspend fun findByNote(noteId: String) =
        items.value.firstOrNull { it.sourceNoteId == noteId && alive(it) }
    override fun observeActive(): Flow<List<ArticleEntity>> =
        kotlinx.coroutines.flow.combine(items, trashed.rows) { all, _ -> all.filter { alive(it) } }
    override suspend fun findAllActive(): List<ArticleEntity> =
        items.value.filter { alive(it) }.sortedByDescending { it.writtenAt }
    override suspend fun findById(id: String): ArticleEntity? =
        items.value.firstOrNull { it.id == id }
    override suspend fun findAll(): List<ArticleEntity> = items.value
    override suspend fun updateContent(id: String, json: String) {
        items.value = items.value.map {
            if (it.id == id) it.copy(paragraphsJson = json, edited = true) else it
        }
    }
    override suspend fun updateTitle(id: String, title: String) {
        items.value = items.value.map { if (it.id == id) it.copy(title = title) else it }
    }
    override suspend fun delete(id: String) {
        items.value = items.value.filterNot { it.id == id }
    }
    fun seed(list: List<ArticleEntity>) { items.value = list }
    fun current(): List<ArticleEntity> = items.value
}

class FakeScriptDao(
    private val trashed: FakeTrashedDao = FakeTrashedDao(),
) : ScriptDao {
    private val items = MutableStateFlow<List<ScriptEntity>>(emptyList())

    private fun alive(e: ScriptEntity) =
        trashed.rows.value.none { it.resourceType == TrashType.SCRIPT && it.resourceId == e.id }

    override suspend fun upsert(entity: ScriptEntity) {
        items.value = items.value.filterNot { it.id == entity.id } + entity
    }
    override suspend fun findByArticle(articleId: String) =
        items.value.filter { it.sourceArticleId == articleId && alive(it) }
    override fun observeActive(): Flow<List<ScriptEntity>> =
        kotlinx.coroutines.flow.combine(items, trashed.rows) { all, _ -> all.filter { alive(it) } }
    override suspend fun findAllActive(): List<ScriptEntity> =
        items.value.filter { alive(it) }.sortedByDescending { it.createdAt }
    override suspend fun findById(id: String): ScriptEntity? =
        items.value.firstOrNull { it.id == id }
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
    private val articleDao = FakeArticleDao(trashedDao)
    private val scriptDao = FakeScriptDao(trashedDao)
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
        assertEquals("必须记录 parentId，否则「从哪来的」这条线就断了", origin, f.parentId)
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

    @Test
    fun `派生之后父记录的状态是已派生`() = runBlocking {
        val origin = repo.createNote("原始", "正文")
        repo.forkFrom(origin)
        val view = repo.findNote(origin)!!
        assertEquals(NoteState.DERIVED, view.state)
    }

    // ---------- 记录状态 ----------

    @Test
    fun `只有记录时状态是记录`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        assertEquals(NoteState.RECORD, repo.observeLibrary().first().notes[0].state)
    }

    @Test
    fun `有追问未完成是深化中`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        deepeningDao.seed(
            listOf(DeepeningEntity("d1", "n1", t, null, 5, """[{"q":"问？","a":null}]"""))
        )
        assertEquals(NoteState.ONGOING, repo.observeLibrary().first().notes[0].state)
    }

    @Test
    fun `追问收尾了是已深化`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        deepeningDao.seed(
            listOf(DeepeningEntity("d1", "n1", t, t + 1, 5, """[{"q":"问？","a":"答"}]"""))
        )
        assertEquals(NoteState.DEEPENED, repo.observeLibrary().first().notes[0].state)
    }

    @Test
    fun `有文章时记录是已成文`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        assertEquals(NoteState.COMPOSED, repo.observeLibrary().first().notes[0].state)
    }

    /**
     * 这条是拆分的核心：选题拍没拍，不该改变记录显示什么。
     *
     * 旧版把 8 个状态混在一起，一条老记录会显示「已过时」——
     * 而那个判定居然取决于**新那条**有没有拍完。
     */
    @Test
    fun `选题已拍摄不改变记录的状态`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        scriptDao.seed(listOf(seedScript("a_n1")))

        repo.markShot("s_a_n1")

        val lib = repo.observeLibrary().first()
        assertEquals("记录只知道自己成了文", NoteState.COMPOSED, lib.notes[0].state)
        assertEquals("拍摄状态属于选题", TopicState.SHOT, lib.topics[0].state)
    }

    // ---------- 三个列表各自排序 ----------

    @Test
    fun `记录列表含全部记录且最新在前`() = runBlocking {
        noteDao.seed(listOf(seedNote("old", t), seedNote("new", t + 9999)))
        val notes = repo.observeLibrary().first().notes
        assertEquals(2, notes.size)
        assertEquals("我刚记的应该是最显眼的那条", "new", notes[0].note.id)
    }

    @Test
    fun `已成文的记录仍在记录列表里`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1", t)))
        articleDao.seed(listOf(seedArticle("n1")))
        val notes = repo.observeLibrary().first().notes
        assertEquals("已成文的记录也该留着", 1, notes.size)
        assertEquals("n1", notes[0].note.id)
    }

    @Test
    fun `思考库是文章列表而不是记录列表`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1"), seedNote("n2")))
        articleDao.seed(listOf(seedArticle("n2")))

        val articles = repo.observeLibrary().first().articles
        assertEquals(1, articles.size)
        assertEquals("a_n2", articles[0].article.id)
    }

    @Test
    fun `文章按成稿时间倒序`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1"), seedNote("n2")))
        articleDao.seed(
            listOf(
                seedArticle("n1").copy(writtenAt = t),
                seedArticle("n2").copy(writtenAt = t + 5000),
            )
        )
        val articles = repo.observeLibrary().first().articles
        assertEquals("a_n2", articles[0].article.id)
    }

    @Test
    fun `没有选题时选题列表是空的`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        assertTrue(repo.observeLibrary().first().topics.isEmpty())
    }

    @Test
    fun `文章知道有几个选题`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        scriptDao.seed(listOf(seedScript("a_n1")))
        val article = repo.findArticle("a_n1")!!
        assertEquals(1, article.topicCount)
    }

    // ---------- 关键约束：保存 ≠ 标记已拍摄 ----------

    @Test
    fun `保存备注不改变拍摄状态`() = runBlocking {
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
        scriptDao.seed(listOf(seedScript("a_n1")))
        repo.markShot("s_a_n1")
        assertNotNull("右滑是唯一标记已拍摄的入口", scriptDao.get("s_a_n1")!!.shotAt)
    }

    @Test
    fun `撤销后拍摄状态清空`() = runBlocking {
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
    fun `搁置后选题状态是搁置`() = runBlocking {
        scriptDao.seed(listOf(seedScript("a_n1")))
        repo.parkScript("s_a_n1")
        assertEquals(TopicState.PARKED, repo.observeLibrary().first().topics[0].state)
    }

    @Test
    fun `待开拍的选题排在已拍摄前面`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1"), seedNote("n2")))
        articleDao.seed(listOf(seedArticle("n1"), seedArticle("n2")))
        scriptDao.seed(listOf(seedScript("a_n1"), seedScript("a_n2")))

        repo.markShot("s_a_n1")

        val topics = repo.observeLibrary().first().topics
        assertEquals(2, topics.size)
        assertEquals("待开拍的在前面", "s_a_n2", topics[0].script.id)
        assertEquals("已拍摄的沉底", "s_a_n1", topics[1].script.id)
    }

    @Test
    fun `搁置的选题排在最后`() = runBlocking {
        scriptDao.seed(listOf(seedScript("a_n1"), seedScript("a_n2")))
        repo.parkScript("s_a_n2")
        val topics = repo.observeLibrary().first().topics
        assertEquals("s_a_n1", topics[0].script.id)
        assertEquals("s_a_n2", topics[1].script.id)
    }

    // ---------- 文章编辑 ----------

    @Test
    fun `编辑文章后仍是同一篇不产生副本`() = runBlocking {
        articleDao.seed(listOf(seedArticle("n1")))
        repo.updateArticleContent("a_n1", listOf(Paragraph("改过的内容", true)))
        val articles = articleDao.current()
        assertEquals("编辑不应产生副本——文章是思考资产", 1, articles.size)
        assertEquals("a_n1", articles[0].id)
    }

    @Test
    fun `编辑后标记为已编辑`() = runBlocking {
        articleDao.seed(listOf(seedArticle("n1")))
        repo.updateArticleContent("a_n1", listOf(Paragraph("改过的", true)))
        assertTrue("edited 标记让「来源」视图知道这段是用户改的", articleDao.current()[0].edited)
    }

    @Test
    fun `编辑后内容能被正确读回`() = runBlocking {
        articleDao.seed(listOf(seedArticle("n1")))
        repo.updateArticleContent(
            "a_n1",
            listOf(Paragraph("新内容一", true), Paragraph("新内容二", false)),
        )
        val article = repo.findArticle("a_n1")!!.article
        assertEquals(2, article.paragraphs.size)
        assertEquals("新内容一", article.paragraphs[0].text)
        assertFalse(article.paragraphs[1].mine)
    }

    // ---------- 三种资源互相独立 ----------

    /**
     * 资源独立的核心断言。
     *
     * 以前删一条记录会连带删掉它的文章和所有选题（ON DELETE CASCADE）。
     * 现在 note 删了，article 和 script 只是失去出处，自己照常在。
     */
    @Test
    fun `彻底删掉记录之后文章还在`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        scriptDao.seed(listOf(seedScript("a_n1")))

        repo.purge(TrashType.NOTE, "n1")

        assertNull(noteDao.findById("n1"))
        assertNotNull("文章是资产，不该跟着记录消失", repo.findArticle("a_n1"))
        assertEquals("选题也不该跟着消失", 1, repo.observeLibrary().first().topics.size)
    }

    @Test
    fun `彻底删掉文章之后选题还在`() = runBlocking {
        articleDao.seed(listOf(seedArticle("n1")))
        scriptDao.seed(listOf(seedScript("a_n1")))

        repo.purge(TrashType.ARTICLE, "a_n1")

        assertNull(articleDao.findById("a_n1"))
        assertEquals(1, repo.observeLibrary().first().topics.size)
    }

    @Test
    fun `记录被删后文章仍能显示原文快照`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        repo.purge(TrashType.NOTE, "n1")

        val article = repo.findArticle("a_n1")!!
        assertEquals("正文n1", article.article.sourceText)
        assertTrue("出处记录没了，界面要能说「原始记录已删除」", !article.hasSource)
    }

    /**
     * 「有没有出处」和「出处还在不在」是两件事，必须分开。
     *
     * 前者是生成那一刻的快照（`sourceNoteId` 非空），决定界面要不要画出处区块；
     * 后者是当下查询的结果，决定那个区块能不能点。
     * 只留一个布尔值的话，记录被删之后界面会画一个点进去是空的入口。
     */
    @Test
    fun `出处已删仍保留曾经有出处这件事实`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))
        repo.purge(TrashType.NOTE, "n1")

        val article = repo.findArticle("a_n1")!!
        assertTrue("曾经从记录生成——快照段要能画出来", article.hadSource)
        assertTrue("但那条记录现在点不过去", !article.hasSource)
    }

    @Test
    fun `记录还在时文章能跳回出处`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))

        val article = repo.findArticle("a_n1")!!
        assertTrue(article.hadSource)
        assertTrue("记录就在库里，出处入口要能点", article.hasSource)
    }

    /**
     * 列表走的是 `assemble()` 另一条路径。
     *
     * `findArticle` 修好了不代表列表修好了——两条路各算一次，
     * 而用户看到的是列表。
     */
    @Test
    fun `列表里也要标出处还在不在`() = runBlocking {
        noteDao.seed(listOf(seedNote("n1")))
        articleDao.seed(listOf(seedArticle("n1")))

        assertTrue(repo.observeLibrary().first().articles.first().hasSource)

        repo.purge(TrashType.NOTE, "n1")

        val after = repo.observeLibrary().first().articles.first()
        assertTrue("记录删了，列表里的出处入口必须变灰", !after.hasSource)
        assertTrue("但文章本身还在", after.hadSource)
    }

    @Test
    fun `文章进回收站后不在思考库里`() = runBlocking {
        articleDao.seed(listOf(seedArticle("n1")))
        repo.moveToTrash(TrashType.ARTICLE, "a_n1")
        assertTrue(repo.observeLibrary().first().articles.isEmpty())
    }

    @Test
    fun `选题进回收站后不在选题库里`() = runBlocking {
        scriptDao.seed(listOf(seedScript("a_n1")))
        repo.moveToTrash(TrashType.SCRIPT, "s_a_n1")
        assertTrue(repo.observeLibrary().first().topics.isEmpty())
    }

    /**
     * 保存是「资源还活着」的信号。
     *
     * 写回用的是确定性 id（a_$noteId），如果不清掉回收站记录，
     * 会出现「文章在库里、却还挂在回收站列表里」的鬼状态。
     */
    @Test
    fun `重新保存文章会把它从回收站里取出来`() = runBlocking {
        articleDao.seed(listOf(seedArticle("n1")))
        repo.moveToTrash(TrashType.ARTICLE, "a_n1")

        repo.saveArticle(
            Article(
                id = "a_n1",
                sourceNoteId = "n1",
                sourceText = "正文n1",
                title = "重写的",
                paragraphs = listOf(Paragraph("新", true)),
                writtenAt = t,
            )
        )

        assertTrue("存回去就该从回收站消失", trashedDao.findAll().isEmpty())
        assertFalse(repo.observeLibrary().first().articles.isEmpty())
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
        assertTrue(repo.observeLibrary().first().notes.isNotEmpty())
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
        assertNull(repo.findNote("不存在"))
    }

    @Test
    fun `查找不存在的文章返回 null`() = runBlocking {
        assertNull(repo.findArticle("不存在"))
    }

    @Test
    fun `空的库里三个列表都为空`() = runBlocking {
        val lib = repo.observeLibrary().first()
        assertTrue(lib.notes.isEmpty())
        assertTrue(lib.articles.isEmpty())
        assertTrue(lib.topics.isEmpty())
        assertTrue(lib.isEmpty)
    }
}
