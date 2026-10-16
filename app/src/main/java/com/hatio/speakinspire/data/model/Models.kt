package com.hatio.speakinspire.data.model

/**
 * 随手记。永不变更——任何后续加工都发生在派生表里。
 * 对应设计文档：note 表不存 status，8 个状态全部由关联派生计算。
 */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val source: NoteSource = NoteSource.QUICK,
    val parentId: String? = null
)

enum class NoteSource(val label: String) {
    QUICK("随手记"),
    INSIGHT("有感而发"),
    READING("读书/播客"),
    CONFLICT("跟人聊了之后")
}

/**
 * 一次追问会话。note 与 deepening 是 1:N——同一条笔记可以深化多次。
 * rounds 为空表示尚未开始（预估轮数由 AiService 返回）。
 */
data class Deepening(
    val id: String,
    val noteId: String,
    val rounds: List<Round>,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val estimatedRounds: Int = 5
)

data class Round(
    val question: String,
    val answer: String? = null
) {
    val answered: Boolean get() = !answer.isNullOrBlank()
}

/**
 * AI 成稿。永久留存于思考库——是资产不是消耗品。
 * 段落级 mineCount 用于「来源」视图：区分用户原话与 AI 补充。
 */
data class Article(
    val id: String,
    val noteId: String,
    val title: String,
    val paragraphs: List<Paragraph>,
    val writtenAt: Long,
    val edited: Boolean = false
)

data class Paragraph(
    val text: String,
    /** true = 用户在追问里说过的；false = 成文阶段 AI 补充的论证 */
    val mine: Boolean
)

/**
 * 拍摄文案。挂在文章之下，是可派生的拍摄计划——
 * 同一篇文章可以转成多个选题（不同角度）。
 */
data class Script(
    val id: String,
    val articleId: String,
    val angle: String,
    val sections: List<Section>,
    val createdAt: Long,
    val shotAt: Long? = null,
    val parked: Boolean = false,
    val note: String = ""
)

data class Section(
    val title: String,
    val seconds: Int,
    /** 意群短语，不是孤立词——孤立词对组句困难的用户负荷过高 */
    val phrases: List<String>,
    val cues: List<Cue> = emptyList()
)

data class Cue(val icon: String, val text: String)