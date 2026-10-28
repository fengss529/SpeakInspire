package com.hatio.speakinspire.data.local

import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.Note
import com.hatio.speakinspire.data.model.NoteSource
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.data.model.Sprout

/**
 * Entity ↔ Model 映射。纯函数，无 Android 依赖——最容易测，也最容易错。
 *
 * 单一真相来源：数据模型定义语义，实体只是存储布局。
 * 所有 JSON 编解码都收敛在这里，DAO 层不碰序列化细节。
 */

fun NoteEntity.toModel(): Note = Note(
    id = id,
    title = title,
    body = body,
    createdAt = createdAt,
    // 存的是枚举 name，读不认识的值要兜底——
    // 将来新增枚举值时，旧数据不该让整个列表崩掉
    source = runCatching { NoteSource.valueOf(source) }.getOrDefault(NoteSource.QUICK),
    parentId = parentId,
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id,
    title = title,
    body = body,
    createdAt = createdAt,
    source = source.name,
    parentId = parentId,
)

fun DeepeningEntity.toModel(): Deepening = Deepening(
    id = id,
    noteId = noteId,
    rounds = SimpleJson.decodeRounds(roundsJson),
    startedAt = startedAt,
    finishedAt = finishedAt,
    estimatedRounds = estimatedRounds,
)

fun Deepening.toEntity(): DeepeningEntity = DeepeningEntity(
    id = id,
    noteId = noteId,
    startedAt = startedAt,
    finishedAt = finishedAt,
    estimatedRounds = estimatedRounds,
    roundsJson = SimpleJson.encodeRounds(rounds),
)

fun ArticleEntity.toModel(): Article = Article(
    id = id,
    sourceNoteId = sourceNoteId,
    sourceText = sourceText,
    title = title,
    paragraphs = SimpleJson.decodeParagraphs(paragraphsJson),
    writtenAt = writtenAt,
    edited = edited,
)

fun Article.toEntity(): ArticleEntity = ArticleEntity(
    id = id,
    sourceNoteId = sourceNoteId,
    sourceText = sourceText,
    title = title,
    paragraphsJson = SimpleJson.encodeParagraphs(paragraphs),
    writtenAt = writtenAt,
    edited = edited,
)

fun ScriptEntity.toModel(): Script = Script(
    id = id,
    sourceArticleId = sourceArticleId,
    sourceTitle = sourceTitle,
    angle = angle,
    sections = SimpleJson.decodeSections(sectionsJson),
    createdAt = createdAt,
    shotAt = shotAt,
    parked = parked,
    note = note,
)

fun Script.toEntity(): ScriptEntity = ScriptEntity(
    id = id,
    sourceArticleId = sourceArticleId,
    sourceTitle = sourceTitle,
    angle = angle,
    rationale = "",
    sectionsJson = SimpleJson.encodeSections(sections),
    createdAt = createdAt,
    shotAt = shotAt,
    parked = parked,
    note = note,
)

fun SproutEntity.toModel(): Sprout = Sprout(
    id = id,
    noteId = noteId,
    title = title,
    sections = SimpleJson.decodeSproutSections(sectionsJson),
    createdAt = createdAt,
)

fun Sprout.toEntity(): SproutEntity = SproutEntity(
    id = id,
    noteId = noteId,
    title = title,
    sectionsJson = SimpleJson.encodeSproutSections(sections),
    createdAt = createdAt,
)

/**
 * 记录视图。**注意 note 表没有 status**，状态是算出来的。
 *
 * [articleId] 是可空的——文章是独立资源，可能还没生成，
 * 也可能已经被单独删掉了。这里不做存在性校验，
 * 因为「记录知道我生成过文章」和「文章还在不在」是两件事。
 */
fun NoteEntity.toView(
    deepening: DeepeningEntity?,
    sprouts: List<SproutEntity>,
    articleId: String?,
    hasChild: Boolean,
): com.hatio.speakinspire.domain.NoteView =
    com.hatio.speakinspire.domain.NoteView(
        note = toModel(),
        deepening = deepening?.toModel(),
        sprouts = sprouts.map { it.toModel() },
        articleId = articleId,
        hasChild = hasChild,
    )

/**
 * 文章视图。
 *
 * [sourceNoteExists] 必须由调用方查过 note 表后传入——
 * 不能用 `article.sourceNoteId != null` 代替。那个字段是普通列，
 * 记录被删之后它还是非空的，会让界面永远显示「出处还在」。
 */
fun ArticleEntity.toView(
    topicCount: Int,
    sourceNoteExists: Boolean = false,
): com.hatio.speakinspire.domain.ArticleView =
    com.hatio.speakinspire.domain.ArticleView(
        article = toModel(),
        topicCount = topicCount,
        sourceNoteExists = sourceNoteExists,
    )

fun ScriptEntity.toView(): com.hatio.speakinspire.domain.TopicView =
    com.hatio.speakinspire.domain.TopicView(script = toModel())