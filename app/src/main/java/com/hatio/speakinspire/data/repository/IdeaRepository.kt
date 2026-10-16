package com.hatio.speakinspire.data.repository

import com.hatio.speakinspire.data.local.ArticleDao
import com.hatio.speakinspire.data.local.DeepeningDao
import com.hatio.speakinspire.data.local.EntityBundle
import com.hatio.speakinspire.data.local.NoteDao
import com.hatio.speakinspire.data.local.NoteEntity
import com.hatio.speakinspire.data.local.ScriptDao
import com.hatio.speakinspire.data.local.SimpleJson
import com.hatio.speakinspire.data.local.TrashedEntity
import com.hatio.speakinspire.data.local.toEntity
import com.hatio.speakinspire.data.local.toIdeaView
import com.hatio.speakinspire.data.local.toModel
import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.NoteSource
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.domain.IdeaStateDeriver
import com.hatio.speakinspire.domain.IdeaView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 想法仓库 —— 把三张表聚合成 [IdeaView]。
 *
 * ## 为什么要聚合而不是让 UI 分别查三张表
 *
 * 分三次查会在查询之间产生**不一致窗口**：比如正好在第二次查询前
 * 用户右滑了，UI 会短暂显示「待深化」但其实已拍摄。
 * 一次查出完整 bundle 再算状态，天然避免这个问题。
 */
class IdeaRepository(
    private val noteDao: NoteDao,
    private val deepeningDao: DeepeningDao,
    private val articleDao: ArticleDao,
    private val scriptDao: ScriptDao,
) {

    /**
     * 观察全部想法（已排除回收站）。
     *
     * 注意这里 join 了四路查询——任何一张表变化都会触发重算，
     * 所以 8 个派生状态永远是算出来的，不存在过期的缓存。
     */
    fun observeIdeas(): Flow<List<IdeaView>> = combine(
        noteDao.observeActive(),
        deepeningDao.observeAll(),
        articleDao.observeAll(),
        scriptDao.observeAll(),
    ) { notes, deepenings, articles, scripts ->
        val deepeningByNote = deepenings.groupBy { it.noteId }
        val articleByNote = articles.associateBy { it.noteId }
        val scriptsByArticle = scripts.groupBy { it.articleId }

        notes.map { entity ->
            val note = entity.toModel()
            val article = articleByNote[note.id]
            note.toIdeaView(
                EntityBundle(
                    deepening = deepeningByNote[note.id]?.firstOrNull(),
                    article = article,
                    scripts = article?.let { scriptsByArticle[it.id] } ?: emptyList(),
                )
            )
        }
    }

    /** 「深化」tab 用——只含待深化的，按创建时间正序 */
    fun observeDeepeningQueue(): Flow<List<IdeaView>> =
        observeIdeas().map { IdeaStateDeriver.deepeningQueue(it) }

    /** 「思考库」用——适合拍的排前面 */
    fun observeLibrary(): Flow<List<IdeaView>> =
        observeIdeas().map { IdeaStateDeriver.libraryOrder(it) }

    /** 「选题」tab 用——待开拍在前，已拍摄沉底 */
    fun observeTopics(): Flow<List<IdeaView>> =
        observeIdeas().map { IdeaStateDeriver.topicOrder(it) }

    suspend fun findIdea(noteId: String): IdeaView? {
        val entity = noteDao.findById(noteId) ?: return null
        val article = articleDao.findByNote(noteId)
        val scripts = article?.let { scriptDao.findByArticle(it.id) } ?: emptyList()
        return entity.toModel().toIdeaView(
            EntityBundle(
                deepening = deepeningDao.findLatestByNote(noteId),
                article = article,
                scripts = scripts,
            )
        )
    }

    suspend fun createNote(
        title: String,
        body: String,
        source: NoteSource =
            NoteSource.QUICK,
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

    suspend fun saveArticle(article: Article) {
        articleDao.upsert(article.toEntity())
    }

    /** 文章编辑。改完仍是同一篇，不产生副本——它是思考资产 */
    suspend fun updateArticleContent(
        articleId: String,
        paragraphs: List<Paragraph>,
    ) {
        val json = SimpleJson.encodeParagraphs(paragraphs)
        articleDao.updateContent(articleId, json)
    }

    suspend fun saveScript(script: Script) {
        scriptDao.upsert(script.toEntity())
    }

    // ---------- 选题状态：与「保存」严格分开 ----------

    /** 右滑 = 标记已拍摄。这是**唯一**改变 shotAt 的入口 */
    suspend fun markShot(scriptId: String) {
        scriptDao.markShot(scriptId, System.currentTimeMillis())
    }

    /** 撤回标记（7秒撤销条 / 统计页手动撤回） */
    suspend fun undoShot(scriptId: String) = scriptDao.undoShot(scriptId)

    /** 左滑 = 移回思考库。搁置而非删除——文章本身没错 */
    suspend fun parkScript(scriptId: String) = scriptDao.park(scriptId)

    suspend fun unparkScript(scriptId: String) = scriptDao.unpark(scriptId)

    /** 「保存」只管文案，不碰拍摄状态 */
    suspend fun updateScriptNote(scriptId: String, note: String) {
        val scripts = scriptDao.findAll()
        val entity = scripts.firstOrNull { it.id == scriptId } ?: return
        scriptDao.updateContent(scriptId, entity.sectionsJson, note)
    }

    // ---------- 删除与回收站 ----------

    suspend fun moveToTrash(noteId: String, originalIndex: Int = 0) {
        noteDao.trash(
            TrashedEntity(
                noteId = noteId,
                deletedAt = System.currentTimeMillis(),
                originalIndex = originalIndex,
            )
        )
    }

    suspend fun restoreFromTrash(noteId: String) = noteDao.untrash(noteId)

    /** 彻底删除——只在回收站保留期满后由清理任务调用 */
    suspend fun purge(noteId: String) = noteDao.hardDelete(noteId)

    fun observeTrashed(): Flow<List<TrashedEntity>> =
        noteDao.observeTrashed()

    suspend fun purgeExpiredTrash(retentionMs: Long) {
        noteDao.purgeTrashedBefore(System.currentTimeMillis() - retentionMs)
    }
}