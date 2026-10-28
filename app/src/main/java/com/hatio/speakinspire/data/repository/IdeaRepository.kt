package com.hatio.speakinspire.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.hatio.speakinspire.data.local.ArticleDao
import com.hatio.speakinspire.data.local.ArticleEntity
import com.hatio.speakinspire.data.local.DeepeningDao
import com.hatio.speakinspire.data.local.DeepeningEntity
import com.hatio.speakinspire.data.local.NoteDao
import com.hatio.speakinspire.data.local.NoteEntity
import com.hatio.speakinspire.data.local.ScriptDao
import com.hatio.speakinspire.data.local.ScriptEntity
import com.hatio.speakinspire.data.local.SimpleJson
import com.hatio.speakinspire.data.local.SproutDao
import com.hatio.speakinspire.data.local.SproutEntity
import com.hatio.speakinspire.data.local.TrashType
import com.hatio.speakinspire.data.local.TrashedDao
import com.hatio.speakinspire.data.local.TrashedEntity
import com.hatio.speakinspire.data.local.toEntity
import com.hatio.speakinspire.data.local.toModel
import com.hatio.speakinspire.data.local.toView
import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.NoteSource
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.data.model.Sprout
import com.hatio.speakinspire.domain.ArticleView
import com.hatio.speakinspire.domain.Library
import com.hatio.speakinspire.domain.LibraryOrder
import com.hatio.speakinspire.domain.NoteView
import com.hatio.speakinspire.domain.TopicView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/**
 * 三种资源的仓库。
 *
 * ## 为什么仍然一次查全表
 *
 * 三种资源已经独立，但界面仍然要同时看到三个列表（底栏三个 tab 同时在内存里）。
 * 分成三条 Flow 会让同一个 tab 切换触发三次全表查询；
 * 一次 combine 出 [Library]，界面只订阅一次。
 *
 * ## 独立的是生命周期，不是数据完整性
 *
 * `article.sourceNoteId` / `script.sourceArticleId` 是可空普通列，
 * 对方被删掉之后仍然能读到 `sourceText` / `sourceTitle` 快照。
 * 所以这里**不做任何引用完整性校验**——悬空引用是合法状态，不是错误。
 */
class IdeaRepository(
    private val noteDao: NoteDao,
    private val deepeningDao: DeepeningDao,
    private val articleDao: ArticleDao,
    private val scriptDao: ScriptDao,
    private val sproutDao: SproutDao,
    private val trashedDao: TrashedDao,
) {

    /**
     * 观察三种资源的全部内容（已排除回收站）。
     *
     * 五路 combine：任何一张表变化都会触发重算，
     * 所以派生状态永远是算出来的，不存在过期缓存。
     */
    fun observeLibrary(): Flow<Library> = combine(
        noteDao.observeActive(),
        deepeningDao.observeAll(),
        articleDao.observeActive(),
        scriptDao.observeActive(),
        sproutDao.observeAll(),
    ) { notes, deepenings, articles, scripts, sprouts ->
        assemble(notes, deepenings, articles, scripts, sprouts)
    }

    /** 一次性读全库。导出用——导出必须完整，不能只导当前 tab 看得见的那些 */
    suspend fun exportSnapshot(): Library = withContext(Dispatchers.IO) {
        assemble(
            notes = noteDao.findAllActive(),
            deepenings = deepeningDao.findAll(),
            articles = articleDao.findAllActive(),
            scripts = scriptDao.findAllActive(),
            sprouts = sproutDao.findAll(),
        )
    }

    /**
     * 组装三个列表。
     *
     * ⚠️ `sourceNoteId` / `sourceArticleId` 可空，所以**不能**直接 associateBy——
     * null 会变成一个键，把一堆孤儿资源挤在一起。一律先 mapNotNull 丢掉空引用。
     */
    private fun assemble(
        notes: List<NoteEntity>,
        deepenings: List<DeepeningEntity>,
        articles: List<ArticleEntity>,
        scripts: List<ScriptEntity>,
        sprouts: List<SproutEntity>,
    ): Library {
        val deepeningByNote = deepenings.groupBy { it.noteId }
        val sproutsByNote = sprouts.groupBy { it.noteId }
        val articleIdByNote = articles
            .mapNotNull { a -> a.sourceNoteId?.let { it to a.id } }
            .toMap()
        val topicCountByArticle = scripts
            .mapNotNull { s -> s.sourceArticleId?.let { it to 1 } }
            .groupingBy { it.first }
            .eachCount()
        // 谁被当过 parent，谁就有孩子。不用另外查一次表
        val parents = notes.mapNotNull { it.parentId }.toSet()
        // 出处记录还在不在。同样用内存里这份 id 集合判断，不再回表
        val liveNoteIds = notes.map { it.id }.toSet()

        return Library(
            notes = LibraryOrder.notes(
                notes.map { e ->
                    e.toView(
                        deepening = deepeningByNote[e.id]?.firstOrNull(),
                        sprouts = sproutsByNote[e.id].orEmpty(),
                        articleId = articleIdByNote[e.id],
                        hasChild = e.id in parents,
                    )
                }
            ),
            articles = LibraryOrder.articles(
                articles.map { e ->
                    e.toView(
                        topicCount = topicCountByArticle[e.id] ?: 0,
                        sourceNoteExists = e.sourceNoteId?.let { it in liveNoteIds } == true,
                    )
                }
            ),
            topics = LibraryOrder.topics(scripts.map { it.toView() }),
        )
    }

    // ---------------------------------------------------------------- 单条读取

    suspend fun findNote(noteId: String): NoteView? {
        val entity = noteDao.findById(noteId) ?: return null
        return entity.toView(
            deepening = deepeningDao.findLatestByNote(noteId),
            sprouts = sproutDao.findByNote(noteId),
            articleId = articleDao.findByNote(noteId)?.id,
            hasChild = noteDao.hasChild(noteId),
        )
    }

    suspend fun findArticle(articleId: String): ArticleView? {
        val entity = articleDao.findById(articleId) ?: return null
        return entity.toView(
            topicCount = scriptDao.findByArticle(articleId).size,
            sourceNoteExists = noteIsAlive(entity.sourceNoteId),
        )
    }

    /** 从记录找它的文章。两条独立资源之间的普通查询，不是从属关系 */
    suspend fun findArticleOfNote(noteId: String): ArticleView? {
        val entity = articleDao.findByNote(noteId) ?: return null
        return entity.toView(
            topicCount = scriptDao.findByArticle(entity.id).size,
            sourceNoteExists = noteIsAlive(noteId),
        )
    }

    /**
     * 这条记录现在算不算「还在」。
     *
     * 两个条件缺一不可：行还在（`exists`），且没在回收站里。
     * 只看 `exists` 会让「出处已删」的文案在列表和详情页对不上——
     * 列表的 `liveNoteIds` 来自 `observeActive()`，本来就排掉了回收站。
     */
    private suspend fun noteIsAlive(noteId: String?): Boolean {
        if (noteId == null) return false
        if (trashedDao.contains(TrashType.NOTE, noteId)) return false
        return noteDao.exists(noteId)
    }

    suspend fun findTopic(scriptId: String): TopicView? =
        scriptDao.findById(scriptId)?.toView()

    suspend fun topicsOfArticle(articleId: String): List<TopicView> =
        scriptDao.findByArticle(articleId).map { it.toView() }

    // ---------------------------------------------------------------- 写入

    /**
     * 改已有记录的内容。
     *
     * createdAt **不动** —— 改了会让这条记录在时间线上跳到最前面，
     * 而用户只是想改个字，不是在新建一条。
     *
     * 返回是否真的更新了。之前这里是静默 `?: return` —— id 传错时
     * 不报错也不生效，表现为「点了保存但内容没变」，排查起来毫无线索。
     */
    suspend fun updateNote(id: String, title: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            val existing = noteDao.findById(id) ?: return@withContext false
            noteDao.upsert(existing.copy(title = title, body = body))
            true
        }

    suspend fun createNote(
        title: String,
        body: String,
        source: NoteSource = NoteSource.QUICK,
        parentId: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        noteDao.upsert(
            NoteEntity(
                id = id,
                title = title,
                body = body,
                createdAt = System.currentTimeMillis(),
                source = source.name,
                parentId = parentId,
            )
        )
        return id
    }

    /**
     * 以旧笔记为起点新建。
     *
     * 决策背景：文案锁定后若想改主意，唯一出路是重开一条——
     * 但内容必须能带过来，否则「想改」的心理阻力会让用户干脆不重记。
     */
    suspend fun forkFrom(noteId: String): String? {
        val origin = noteDao.findById(noteId) ?: return null
        return createNote(
            title = origin.title,
            body = origin.body,
            source = runCatching {
                NoteSource.valueOf(origin.source)
            }.getOrDefault(NoteSource.QUICK),
            parentId = noteId,
        )
    }

    suspend fun saveDeepening(deepening: Deepening) {
        deepeningDao.upsert(deepening.toEntity())
    }

    suspend fun findDeepening(noteId: String): Deepening? =
        deepeningDao.findLatestByNote(noteId)?.toModel()

    /**
     * 存文章。
     *
     * 顺带把它的回收站记录清掉：能写进去就说明它是活的。
     * 不这么做会出现「文章在库里、却还在回收站列表里」的鬼状态——
     * 因为写回用的是同一个确定性 id（`a_$noteId`）。
     */
    suspend fun saveArticle(article: Article) {
        articleDao.upsert(article.toEntity())
        trashedDao.remove(TrashType.ARTICLE, article.id)
    }

    /** 文章编辑。改完仍是同一篇，不产生副本——它是思考资产 */
    suspend fun updateArticleContent(articleId: String, paragraphs: List<Paragraph>) {
        articleDao.updateContent(articleId, SimpleJson.encodeParagraphs(paragraphs))
    }

    suspend fun updateArticleTitle(articleId: String, title: String) {
        articleDao.updateTitle(articleId, title)
    }

    suspend fun saveScript(script: Script) {
        scriptDao.upsert(script.toEntity())
        trashedDao.remove(TrashType.SCRIPT, script.id)
    }

    /**
     * 存一份发芽报告。
     *
     * 用 insert 而非 upsert：每发一次芽都是新的认知状态，
     * 覆盖掉上一次等于抹掉「我那天是怎么想的」。
     * id 由调用方生成（带时间戳），不会撞。
     */
    suspend fun saveSprout(sprout: Sprout) {
        sproutDao.insert(sprout.toEntity())
    }

    suspend fun deleteSprout(sproutId: String) = sproutDao.delete(sproutId)

    suspend fun findSprout(sproutId: String): Sprout? =
        sproutDao.findById(sproutId)?.toModel()

    suspend fun sproutsOf(noteId: String): List<Sprout> =
        sproutDao.findByNote(noteId).map { it.toModel() }

    // ---------- 选题状态：与「保存」严格分开 ----------

    /** 右滑 = 标记已拍摄。这是**唯一**改变 shotAt 的入口 */
    suspend fun markShot(scriptId: String) {
        scriptDao.markShot(scriptId, System.currentTimeMillis())
    }

    /** 撤回标记（7秒撤销条 / 统计页手动撤回） */
    suspend fun undoShot(scriptId: String) = scriptDao.undoShot(scriptId)

    /** 左滑 = 搁置。搁置而非删除——文章本身没错 */
    suspend fun parkScript(scriptId: String) = scriptDao.park(scriptId)

    suspend fun unparkScript(scriptId: String) = scriptDao.unpark(scriptId)

    /** 「保存」只管文案，不碰拍摄状态 */
    suspend fun updateScriptNote(scriptId: String, note: String) {
        val entity = scriptDao.findById(scriptId) ?: return
        scriptDao.updateContent(scriptId, entity.sectionsJson, note)
    }

    // ---------- 删除与回收站 ----------

    /**
     * 三种资源共用一个回收站，靠 [type] 区分。
     *
     * 注意：**移入回收站不等于删除**。记录进回收站时，
     * 它的追问和发芽仍在（它们靠外键跟着记录走），
     * 只有在回收站里「彻底删除」才会真删。
     */
    suspend fun moveToTrash(type: String, resourceId: String, originalIndex: Int = 0) {
        trashedDao.insert(
            TrashedEntity(
                id = "tr_${type}_$resourceId",
                resourceType = type,
                resourceId = resourceId,
                deletedAt = System.currentTimeMillis(),
                originalIndex = originalIndex,
            )
        )
    }

    suspend fun restoreFromTrash(type: String, resourceId: String) {
        trashedDao.remove(type, resourceId)
    }

    /** 彻底删除。只删指定的那一个资源，**不碰其他资源** */
    suspend fun purge(type: String, resourceId: String) {
        when (type) {
            TrashType.NOTE -> noteDao.hardDelete(resourceId)
            TrashType.ARTICLE -> articleDao.delete(resourceId)
            TrashType.SCRIPT -> scriptDao.delete(resourceId)
            else -> return
        }
        trashedDao.remove(type, resourceId)
    }

    fun observeTrashed(): Flow<List<TrashedEntity>> = trashedDao.observeAll()

    suspend fun findTrashed(): List<TrashedEntity> = trashedDao.findAll()

    /**
     * 回收站列表用：取被删资源的标题。
     *
     * ⚠️ **必须走不带回收站过滤的查询**（`findById`，不是 `findByNote` 之类）。
     * 用 [exportSnapshot] 会拿到空列表——它本来就排掉了回收站里的东西，
     * 于是每一行都显示「找不到了」。
     */
    suspend fun titleOf(type: String, resourceId: String): String = when (type) {
        TrashType.NOTE -> noteDao.findById(resourceId)?.title.orEmpty()
        TrashType.ARTICLE -> articleDao.findById(resourceId)?.title.orEmpty()
        TrashType.SCRIPT -> scriptDao.findById(resourceId)
            ?.let { it.sourceTitle.ifBlank { it.angle } }
            .orEmpty()
        else -> ""
    }

    suspend fun purgeExpiredTrash(retentionMs: Long) {
        val before = System.currentTimeMillis() - retentionMs
        trashedDao.findAll()
            .filter { it.deletedAt < before }
            .forEach { purge(it.resourceType, it.resourceId) }
    }
}
