package com.hatio.speakinspire.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 实体。note 表**不存 status**——8 个状态全部由关联派生计算。
 *
 * 这不是省事，而是刻意的：status 存冗余，一旦某个操作忘了更新，
 * 就会出现「列表显示待深化但实际已拍摄」这类不一致，且极难排查。
 */

@Entity(tableName = "note")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val source: String,
    /** 以此为起点新建时，指向原note */
    val parentId: String?,
)

@Entity(
    tableName = "deepening",
    foreignKeys = [ForeignKey(
        entity = NoteEntity::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("noteId")],
)
data class DeepeningEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val estimatedRounds: Int,
    /** JSON 序列化的List<ChatMessage> */
    val roundsJson: String,
)

@Entity(
    tableName = "article",
    foreignKeys = [ForeignKey(
        entity = NoteEntity::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("noteId")],
)
data class ArticleEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val title: String,
    /** JSON 序列化的 List<DraftParagraph>，含 isMine 标记 */
    val paragraphsJson: String,
    val writtenAt: Long,
    val edited: Boolean,
)

@Entity(
    tableName = "script",
    foreignKeys = [ForeignKey(
        entity = ArticleEntity::class,
        parentColumns = ["id"],
        childColumns = ["articleId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("articleId")],
)
data class ScriptEntity(
    @PrimaryKey val id: String,
    val articleId: String,
    val angle: String,
    val rationale: String,
    /** JSON 序列化的 List<OutlineSection> */
    val sectionsJson: String,
    val createdAt: Long,
    val shotAt: Long?,
    /** 左滑移回思考库：搁置而非删除，文章本身没错 */
    val parked: Boolean,
    val note: String,
)

/** 回收站：删除是可逆的，但没有归档就够了 */
@Entity(tableName = "trashed")
data class TrashedEntity(
    @PrimaryKey val noteId: String,
    val deletedAt: Long,
    val originalIndex: Int,
)

/** 用户自带的服务商配置。key 存EncryptedSharedPreferences，不进 Room。 */
/**
 * 一个服务商下的一个模型配置。
 *
 * **主键是 providerId + model** —— 这样同一个服务商可以存多个模型，
 * 用户能在设置里手动切换（flash / pro / 自定义模型名）。
 *
 * active 标记当前用的是哪个。同一时刻只应有一个 active。
 */
@Entity(
    tableName = "provider",
    primaryKeys = ["providerId", "model"],
)
data class ProviderEntity(
    val providerId: String,
    val baseUrl: String,
    val model: String,
    /** MiMo 的 thinking 默认开启需显式关；DeepSeek 则是换模型名控制 */
    val thinkingDisabled: Boolean,
    val active: Boolean,
    val updatedAt: Long,
)