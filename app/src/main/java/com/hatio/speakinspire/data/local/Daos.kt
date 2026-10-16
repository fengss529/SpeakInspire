package com.hatio.speakinspire.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.Note
import com.hatio.speakinspire.data.model.Script
import kotlinx.coroutines.flow.Flow

/**
 * DAO 层。
 *
 * **note 表刻意不提供 status 的读写**——状态全部由 [com.hatio.speakinspire.domain.IdeaStateDeriver]
 * 从关联数据算出。DAO 里若出现 `UPDATE note SET status=...` 就是设计被破坏了。
 */

@Dao
interface NoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun findById(id: String): NoteEntity?

    @Query("SELECT * FROM note ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    /** 回收站里的笔记不进主列表 */
    @Query(
        """
        SELECT * FROM note
        WHERE id NOT IN (SELECT noteId FROM trashed)
        ORDER BY createdAt DESC
        """
    )
    fun observeActive(): Flow<List<NoteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM note WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("DELETE FROM note WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun trash(entry: TrashedEntity)

    @Query("DELETE FROM trashed WHERE noteId = :noteId")
    suspend fun untrash(noteId: String)

    @Query("SELECT * FROM trashed ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<TrashedEntity>>

    @Query("DELETE FROM trashed WHERE deletedAt < :before")
    suspend fun purgeTrashedBefore(before: Long)
}

@Dao
interface DeepeningDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArticleEntity)

    @Query("SELECT * FROM article WHERE noteId = :noteId LIMIT 1")
    suspend fun findByNote(noteId: String): ArticleEntity?

    @Query("SELECT * FROM article ORDER BY writtenAt DESC")
    fun observeAll(): Flow<List<ArticleEntity>>

    @Query("UPDATE article SET paragraphsJson = :json, edited = 1 WHERE id = :id")
    suspend fun updateContent(id: String, json: String)

    @Query("DELETE FROM article WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ScriptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScriptEntity)

    @Query("SELECT * FROM script WHERE articleId = :articleId ORDER BY createdAt DESC")
    suspend fun findByArticle(articleId: String): List<ScriptEntity>

    @Query("SELECT * FROM script ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ScriptEntity>>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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