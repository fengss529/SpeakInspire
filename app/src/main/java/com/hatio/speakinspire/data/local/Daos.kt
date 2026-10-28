package com.hatio.speakinspire.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * DAO 层。
 *
 * **note 表刻意不提供 status 的读写**——状态全部由 [com.hatio.speakinspire.domain.IdeaStateDeriver]
 * 从关联数据算出。DAO 里若出现 `UPDATE note SET status=...` 就是设计被破坏了。
 *
 * ## ⚠️ 有子表的实体一律用 [Upsert]，绝不能用 `@Insert(onConflict = REPLACE)`
 *
 * 这一条是用用户数据换来的，不要改回去。
 *
 * `INSERT OR REPLACE` 在 SQLite 里的真实语义是 **先 DELETE 再 INSERT**。
 * 而 Room 生成的 `_Impl` 在 `onOpen` 里执行 `PRAGMA foreign_keys = ON`
 * （见 `SpeakInspireDatabase_Impl.java`），所以那次 DELETE 会触发
 * 子表的 `ON DELETE CASCADE`——**父行改一个字，子表整个清空**。
 *
 * 实测后果（真机反馈的两个 bug 是同一件事）：
 * - 用户点「编辑」改原始记录 → `updateNote` → note 行被 REPLACE
 *   → `deepening` 级联删除 → **追问过的所有问答消失**，再打开像从没聊过
 * - 文章 id 是确定性的 `a_$noteId`，重新成文保存 → `article` 行被 REPLACE
 *   → `script` 级联删除 → **选题凭空不见了**
 *
 * [Upsert] 走的是 `INSERT ... ON CONFLICT DO UPDATE`（低版本退化为
 * INSERT+UPDATE），全程不删行，因此不会触发级联。
 *
 * 唯一保留 REPLACE 的是 [TrashedDao.insert]：`trashed` 没有子表，且语义就是覆盖。
 */

@Dao
interface NoteDao {

    /**
     * 新建与改名共用。
     *
     * **必须是 [Upsert]**：改成 REPLACE 会在改名时把这条 note
     * 的追问、文章、选题一起级联删掉（见文件头）。
     */
    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun findById(id: String): NoteEntity?

    @Query("SELECT * FROM note ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    /** 一次性取全部（导出用）。与 [observeActive] 同一条 WHERE，别写成两份规则 */
    @Query(
        """
        SELECT * FROM note
        WHERE id NOT IN (SELECT resourceId FROM trashed WHERE resourceType = 'note')
        ORDER BY createdAt DESC
        """
    )
    suspend fun findAllActive(): List<NoteEntity>

    /** 回收站里的笔记不进主列表 */
    @Query(
        """
        SELECT * FROM note
        WHERE id NOT IN (SELECT resourceId FROM trashed WHERE resourceType = 'note')
        ORDER BY createdAt DESC
        """
    )
    fun observeActive(): Flow<List<NoteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM note WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    /** 有没有从这条记录派生出新记录。用于派生 [com.hatio.speakinspire.domain.NoteState.DERIVED] */
    @Query("SELECT EXISTS(SELECT 1 FROM note WHERE parentId = :id)")
    suspend fun hasChild(id: String): Boolean

    @Query("DELETE FROM note WHERE id = :id")
    suspend fun hardDelete(id: String)
}

@Dao
interface DeepeningDao {

    /** 会话只有一条（id 固定为 `d_$noteId`），整条覆盖是正确语义——但不能用 REPLACE */
    @Upsert
    suspend fun upsert(entity: DeepeningEntity)

    @Query("SELECT * FROM deepening WHERE noteId = :noteId ORDER BY startedAt DESC")
    suspend fun findLatestByNote(noteId: String): DeepeningEntity?

    /** Flow 版供 observeIdeas 聚合用——任何表变化都要触发状态重算 */
    @Query("SELECT * FROM deepening ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<DeepeningEntity>>

    @Query("SELECT * FROM deepening ORDER BY startedAt DESC")
    suspend fun findAll(): List<DeepeningEntity>

    @Query("DELETE FROM deepening WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ArticleDao {

    /**
     * ⚠️ 这里曾经是 REPLACE。
     *
     * 文章 id 是确定性的 `a_$noteId`，所以「重新成文 → 保存」
     * 等于 REPLACE 一条已存在的 article 行 → 级联删掉 `script`
     * → 用户看着选题存好了，回选题 tab 却什么都没有。
     */
    @Upsert
    suspend fun upsert(entity: ArticleEntity)

    @Query(
        """
        SELECT * FROM article
        WHERE sourceNoteId = :noteId
          AND id NOT IN (SELECT resourceId FROM trashed WHERE resourceType = 'article')
        LIMIT 1
        """
    )
    suspend fun findByNote(noteId: String): ArticleEntity?

    /**
     * 三张表各自要从列表里排掉回收站里的那些。
     *
     * 这里用 `NOT IN (SELECT ...)` 而不是 JOIN：回收站表最多几十行，
     * 而主表可能上千行，让 SQLite 走这套子查询比 join 再 group 便宜。
     */
    @Query(
        """
        SELECT * FROM article
        WHERE id NOT IN (SELECT resourceId FROM trashed WHERE resourceType = 'article')
        ORDER BY writtenAt DESC
        """
    )
    fun observeActive(): Flow<List<ArticleEntity>>

    @Query(
        """
        SELECT * FROM article
        WHERE id NOT IN (SELECT resourceId FROM trashed WHERE resourceType = 'article')
        ORDER BY writtenAt DESC
        """
    )
    suspend fun findAllActive(): List<ArticleEntity>

    @Query("SELECT * FROM article WHERE id = :id")
    suspend fun findById(id: String): ArticleEntity?

    @Query("SELECT * FROM article ORDER BY writtenAt DESC")
    suspend fun findAll(): List<ArticleEntity>

    @Query("UPDATE article SET paragraphsJson = :json, edited = 1 WHERE id = :id")
    suspend fun updateContent(id: String, json: String)

    /** 改标题——文章独立之后，「思考」tab 要能编辑自己的标题 */
    @Query("UPDATE article SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("DELETE FROM article WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ScriptDao {

    @Upsert
    suspend fun upsert(entity: ScriptEntity)

    @Query(
        """
        SELECT * FROM script
        WHERE sourceArticleId = :articleId
          AND id NOT IN (SELECT resourceId FROM trashed WHERE resourceType = 'script')
        ORDER BY createdAt DESC
        """
    )
    suspend fun findByArticle(articleId: String): List<ScriptEntity>

    @Query(
        """
        SELECT * FROM script
        WHERE id NOT IN (SELECT resourceId FROM trashed WHERE resourceType = 'script')
        ORDER BY createdAt DESC
        """
    )
    fun observeActive(): Flow<List<ScriptEntity>>

    @Query(
        """
        SELECT * FROM script
        WHERE id NOT IN (SELECT resourceId FROM trashed WHERE resourceType = 'script')
        ORDER BY createdAt DESC
        """
    )
    suspend fun findAllActive(): List<ScriptEntity>

    @Query("SELECT * FROM script WHERE id = :id")
    suspend fun findById(id: String): ScriptEntity?

    @Query("SELECT * FROM script ORDER BY createdAt DESC")
    suspend fun findAll(): List<ScriptEntity>

    /**
     * 右滑 = 标记已拍摄。
     * 注意这是**唯一**改变 shotAt 的入口——「保存」按钮不碰它，
     * 两者职责严格分开（设计决策 Q4）。
     */
    @Query("UPDATE script SET shotAt = :timestamp WHERE id = :id")
    suspend fun markShot(id: String, timestamp: Long)

    /** 撤回标记——7 秒撤销条与统计页的手动撤回都用它 */
    @Query("UPDATE script SET shotAt = NULL WHERE id = :id")
    suspend fun undoShot(id: String)

    /** 左滑 = 移回思考库。搁置而非删除——文章本身没错 */
    @Query("UPDATE script SET parked = 1 WHERE id = :id")
    suspend fun park(id: String)

    @Query("UPDATE script SET parked = 0 WHERE id = :id")
    suspend fun unpark(id: String)

    @Query("UPDATE script SET sectionsJson = :json, note = :note WHERE id = :id")
    suspend fun updateContent(id: String, json: String, note: String)

    @Query("DELETE FROM script WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ProviderDao {

    @Upsert
    suspend fun upsert(entity: ProviderEntity)

    @Query("SELECT * FROM provider WHERE active = 1 LIMIT 1")
    suspend fun findActive(): ProviderEntity?

    @Query("SELECT * FROM provider")
    suspend fun findAll(): List<ProviderEntity>

    @Query("UPDATE provider SET active = 0")
    suspend fun clearActive()

    @Query("UPDATE provider SET active = 1 WHERE providerId = :id")
    suspend fun setActive(id: String)
}

/**
 * 发芽报告。
 *
 * 每次发芽都是一条新行，**不做 upsert 覆盖**——
 * 同一条记录在不同时期发出来的芽完全不同，
 * 覆盖掉等于把上一次的认知收获抹了。
 */
@Dao
interface SproutDao {

    @Insert
    suspend fun insert(entity: SproutEntity)

    @Query("SELECT * FROM sprout WHERE noteId = :noteId ORDER BY createdAt DESC")
    suspend fun findByNote(noteId: String): List<SproutEntity>

    @Query("SELECT * FROM sprout WHERE id = :id")
    suspend fun findById(id: String): SproutEntity?

    @Query("SELECT * FROM sprout ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SproutEntity>>

    @Query("SELECT * FROM sprout ORDER BY createdAt DESC")
    suspend fun findAll(): List<SproutEntity>

    @Query("DELETE FROM sprout WHERE id = :id")
    suspend fun delete(id: String)
}

/**
 * 回收站。**三种资源共用一张表**，靠 `resourceType` 区分。
 *
 * 之所以从 [NoteDao] 里搬出来：只要它还挂在 NoteDao 上，
 * 「删文章」这件事就得绕回记录，那还是捆绑。
 */
@Dao
interface TrashedDao {

    /**
     * 唯一允许 REPLACE 的地方——`trashed` 没有子表，语义就是覆盖。
     * 同一个资源重复删除时不该插出两行。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TrashedEntity)

    @Query("DELETE FROM trashed WHERE resourceType = :type AND resourceId = :resourceId")
    suspend fun remove(type: String, resourceId: String)

    @Query("SELECT * FROM trashed ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashedEntity>>

    @Query("SELECT * FROM trashed ORDER BY deletedAt DESC")
    suspend fun findAll(): List<TrashedEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM trashed WHERE resourceType = :type AND resourceId = :resourceId)"
    )
    suspend fun contains(type: String, resourceId: String): Boolean

    @Query("DELETE FROM trashed WHERE deletedAt < :before")
    suspend fun purgeBefore(before: Long)
}