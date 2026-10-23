package com.hatio.speakinspire.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.hatio.speakinspire.data.local.ArticleDao
import com.hatio.speakinspire.data.local.DeepeningDao
import com.hatio.speakinspire.data.local.EntityBundle
import com.hatio.speakinspire.data.local.NoteDao
import com.hatio.speakinspire.data.local.NoteEntity
import com.hatio.speakinspire.data.local.ScriptDao
import com.hatio.speakinspire.data.local.SimpleJson
import com.hatio.speakinspire.data.local.SproutDao
import com.hatio.speakinspire.data.local.TrashedEntity
import com.hatio.speakinspire.data.local.toEntity
import com.hatio.speakinspire.data.local.toIdeaView
import com.hatio.speakinspire.data.local.toModel
import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.NoteSource
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.data.model.Sprout
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
    private val sproutDao: SproutDao,
) {

    /**
     * 观察全部想法（已排除回收站）。
     *
     * 注意这里 join 了五路查询——任何一张表变化都会触发重算，
     * 所以 8 个派生状态永远是算出来的，不存在过期的缓存。
     */
    fun observeIdeas(): Flow<List<IdeaView>> = combine(
        noteDao.observeActive(),
        deepeningDao.observeAll(),
        articleDao.observeAll(),
        scriptDao.observeAll(),
        sproutDao.observeAll(),
    ) { notes, deepenings, articles, scripts, sprouts ->
        val deepeningByNote = deepenings.groupBy { it.noteId }
        val articleByNote = articles.associateBy { it.noteId }
        val scriptsByArticle = scripts.groupBy { it.articleId }
        val sproutsByNote = sprouts.groupBy { it.noteId }

        notes.map { entity ->
            val note = entity.toModel()
            val article = articleByNote[note.id]
            note.toIdeaView(
                EntityBundle(
                    deepening = deepeningByNote[note.id]?.firstOrNull(),
                    article = article,
                    scripts = article?.let { scriptsByArticle[it.id] } ?: emptyList(),
                    sprouts = sproutsByNote[note.id] ?: emptyList(),
                )
            )
        }
    }

    /**
     * 「记录」tab 用——全部记录，时间倒序。
     *
     * 保留一个具名入口而不是让 UI 自己排序：
     * 排序规则属于领域知识（见 [IdeaStateDeriver.recordTimeline]），
     * 让界面各自 sortedByDescending 迟早会漂移。
     */
    fun observeTimeline(): Flow<List<IdeaView>> =
        observeIdeas().map { IdeaStateDeriver.recordTimeline(it) }

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
                sprouts = sproutDao.findByNote(noteId),
            )
        )
    }

    /** 全部想法 + 全部关联数据，供导出用。一次查全，避免导出时漏表 */
    suspend fun exportSnapshot(): List<IdeaView> =
        withContext(Dispatchers.IO) {
            val notes = noteDao.findAllActive()
            val deepenings = deepeningDao.findAll().groupBy { it.noteId }
            val articles = articleDao.findAll().associateBy { it.noteId }
            val scripts = scriptDao.findAll().groupBy { it.articleId }
            val sprouts = sproutDao.findAll().groupBy { it.noteId }
            notes.map { entity ->
                val note = entity.toModel()
                val article = articles[note.id]
                note.toIdeaView(
                    EntityBundle(
                        deepening = deepenings[note.id]?.firstOrNull(),
                        article = article,
                        scripts = article?.let { scripts[it.id] } ?: emptyList(),
                        sprouts = sprouts[note.id] ?: emptyList(),
                    )
                )
            }
        }

    /**
     * 改已有记录的内容。
     *
     * createdAt **不动** —— 改了会让这条记录在时间线上跳到最前面，
     * 而用户只是想改个字，不是在新建一条。
     *
     * 返回是否真的更新了。
     *
     * 之前这里是静默 `?: return` —— id 传错（比如空字符串）时
     * 不报错也不生效，表现为「点了保存但内容没变」，
     * 排查起来毫无线索。现在返回 false，调用方可以报错。
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