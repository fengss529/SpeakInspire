package com.hatio.speakinspire.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 实体。note / article / script 是**三种独立资源**，
 * 状态各自派生，不跨资源（详见 domain/ResourceState.kt）。
 *
 * 不存冗余 status：一旦某个操作忘了更新，就会出现
 * 「列表显示待深化但实际已拍摄」这类不一致，且极难排查。
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

/**
 * 文章。
 *
 * **没有外键，指向 note 的只是一列普通的 [sourceNoteId]。**
 *
 * 这是 V0.10 的核心决定：记录和文章是两种独立资源。
 * 记录被删掉之后，文章要照常能读、能改、能转选题——
 * 它是那次思考的产物，产物本身可以离开原料活下去。
 *
 * [sourceText] 是生成那一刻的记录原文快照，理由同上：
 * 原始记录删了，文章里「这段说的事」还得有个地方能查。
 */
@Entity(
    tableName = "article",
    indices = [Index("sourceNoteId")],
)
data class ArticleEntity(
    @PrimaryKey val id: String,
    /** 出处记录 id。无外键，可能悬空——UI 据此显示「原始记录已删除」 */
    val sourceNoteId: String?,
    /** 生成时的记录原文快照。空串表示早于 v4 且原文已不可考 */
    val sourceText: String,
    val title: String,
    /** JSON 序列化的 List<Paragraph>，含 mine 标记 */
    val paragraphsJson: String,
    val writtenAt: Long,
    val edited: Boolean,
    /**
     * 这篇按哪种风格写的（`DraftStyle.id`）。空串 = 没记录过。
     *
     * v5 新增。旧文章补空串而不是默认「随感」——
     * 那时候还没有风格这回事，硬塞一个默认值等于替用户记错了一件事。
     */
    val styleId: String,
)

/**
 * 选题。同样**没有外键**，指向文章的是普通列 [sourceArticleId]。
 *
 * 选题独立的意义：文章删了，已经拆好的分镜还能拿去拍。
 * [sourceTitle] 让选题脱离文章后仍能自报家门。
 */
@Entity(
    tableName = "script",
    indices = [Index("sourceArticleId")],
)
data class ScriptEntity(
    @PrimaryKey val id: String,
    /** 出处文章 id。无外键，可能悬空 */
    val sourceArticleId: String?,
    /** 文章标题快照 */
    val sourceTitle: String,
    val angle: String,
    val rationale: String,
    /** JSON 序列化的 List<Section> */
    val sectionsJson: String,
    /**
     * JSON 序列化的 List<Material>。
     *
     * v5 新增。存成 JSON 而不是单开一张表：素材永远跟着选题整体读、
     * 整体写，没有「单独查某一条素材」的需要；
     * 单开一张表只会多一次 join 和一次外键级联的风险。
     */
    val materialsJson: String,
    val createdAt: Long,
    val shotAt: Long?,
    /** 搁置：先不拍。**不再是「移回思考库」**——它就是选题自己的一个状态 */
    val parked: Boolean,
    val note: String,
)

/**
 * 回收站。**能装三种资源**，所以主键不再是 noteId。
 *
 * 为什么不是只给 note 做：文章和选题是模型花钱花时间生成的，
 * 误删一次就没了，比丢一条随手记疼得多。
 */
@Entity(
    tableName = "trashed",
    indices = [Index(value = ["resourceType", "resourceId"], unique = true)],
)
data class TrashedEntity(
    @PrimaryKey val id: String,
    /** [TrashType] 的取值：note / article / script */
    val resourceType: String,
    val resourceId: String,
    val deletedAt: Long,
    /** 恢复时按原位置插回，目前只有记录列表用得上 */
    val originalIndex: Int,
)

object TrashType {
    const val NOTE = "note"
    const val ARTICLE = "article"
    const val SCRIPT = "script"
}

/**
 * 发芽报告。
 *
 * 一条记录可以发很多次芽（不同时期想到的不一样），所以是 1:N。
 * 外键挂 note 并级联——记录没了，它的芽也留不住，
 * 因为种子的原话已经不在了，报告里每节的 `seed` 都指向一个不存在的东西。
 */
@Entity(
    tableName = "sprout",
    foreignKeys = [ForeignKey(
        entity = NoteEntity::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("noteId")],
)
data class SproutEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val title: String,
    /** JSON 序列化的 List<SproutSection> */
    val sectionsJson: String,
    val createdAt: Long,
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