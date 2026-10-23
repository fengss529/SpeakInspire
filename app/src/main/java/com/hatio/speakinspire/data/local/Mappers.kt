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
    noteId = noteId,
    title = title,
    paragraphs = SimpleJson.decodeParagraphs(paragraphsJson),
    writtenAt = writtenAt,
    edited = edited,
)

fun Article.toEntity(): ArticleEntity = ArticleEntity(
    id = id,
    noteId = noteId,
    title = title,
    paragraphsJson = SimpleJson.encodeParagraphs(paragraphs),
    writtenAt = writtenAt,
    edited = edited,
)

fun ScriptEntity.toModel(): Script = Script(
    id = id,
    articleId = articleId,
    angle = angle,
    sections = SimpleJson.decodeSections(sectionsJson),
    createdAt = createdAt,
    shotAt = shotAt,
    parked = parked,
    note = note,
)

fun Script.toEntity(): ScriptEntity = ScriptEntity(
    id = id,
    articleId = articleId,
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

/** 注意这个函数：note 表没有 status，状态是算出来的 */
fun EntityBundle.toIdeaView(note: Note): com.hatio.speakinspire.domain.IdeaView =
    com.hatio.speakinspire.domain.IdeaView(
        note = note,
        deepening = deepening?.toModel(),
        article = article?.toModel(),
        scripts = scripts.map { it.toModel() },
        sprouts = sprouts.map { it.toModel() },
    )

/**
 * 以 note 为主语组装——更符合调用直觉：
 * 「这条 note 对应的 idea view 是什么」。
 */
fun Note.toIdeaView(bundle: EntityBundle): com.hatio.speakinspire.domain.IdeaView =
    bundle.toIdeaView(this)

/**
 * 各表查出来的一次性聚合。
 *
 * UI 只读这个聚合，不分别查各个表——避免多次查询之间状态不一致
 * （比如正好在两次查询之间右滑了）。
 */
data class EntityBundle(
    val deepening: DeepeningEntity?,
    val article: ArticleEntity?,
    val scripts: List<ScriptEntity>,
    /** 发芽报告。默认空，让只关心前四张表的测试不必每次都构造它 */
    val sprouts: List<SproutEntity> = emptyList(),
)