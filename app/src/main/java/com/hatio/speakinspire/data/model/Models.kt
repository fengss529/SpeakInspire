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

    /**
     * 手写 toString —— 不让默认实现泄漏到界面上。
     *
     * 数据类的默认 toString 会把内部结构原样打印成
     * `Round(question=你说的"好奇心"，具体指…, answer=…)`。
     * 详情页曾经写 `"已追问 $rounds 轮"`（漏了 `.size`），
     * 于是整串结构直接显示给用户看。
     *
     * 那个 bug 应该改插值本身；这里再做一层，是保证**万一**再有人
     * 往文案里插一个 Round 或 Round 列表，屏幕上出现的是人话。
     */
    override fun toString(): String =
        if (answered) "问：$question\n答：$answer" else "问：$question"
}

/**
 * 一次「发芽」。
 *
 * ## 这是唯一允许 AI 主动给内容的地方
 *
 * 追问阶段 AI 只问不答——因为那时要做的是**让用户自己想到**。
 * 发芽恰好相反：它的产物不是结论，是**参照物**。
 * 用户记下「既往不恋，当下不杂」，发芽告诉他爱因斯坦也说过
 * 「我从不去想未来，因为它来得已经够快了」、《功夫熊猫》里乌龟大师
 * 也说过类似的话——这不是替他思考，是把他的想法放到更大的坐标系里，
 * 让他自己看见「原来这条感悟不只属于我」。
 *
 * 所以边界不是「AI 不许说话」，而是：
 * - 不许替用户下结论（不能写「所以你应该……」）
 * - 引用的东西必须有出处，编不出来就说不知道
 * - 每一节都必须从他的原话出发，而不是泛泛谈这个主题
 *
 * ## 为什么不落成「文章」
 *
 * 文章是**成果**，指向外部表达；发芽是**素材**，指向内部生长。
 * 混在一起，用户会不知道该把这东西当作品还是当线索。
 * 所以它单独一张表，将来可以顺手记一句变成新记录——
 * 记录 → 发芽 → 新记录，这才是「种子」的语义。
 */
data class Sprout(
    val id: String,
    val noteId: String,
    val title: String,
    val sections: List<SproutSection>,
    val createdAt: Long,
)

/**
 * 发芽报告的一节。
 *
 * 三段式：**他说的（种子）→ 长出来的（连接）→ 出处**。
 * 顺序不能变——先回到他自己的话，才谈得上「从他这里长出去」。
 */
data class SproutSection(
    /** 从他记录或回答里摘出的原话。必须是他的句子，不是转述 */
    val seed: String,
    /** 由这句话延伸出的连接、例子、不同领域里相似的想法 */
    val insight: String,
    /** 出处。什么人、什么书、什么作品。编不出来就留空，不许硬凑 */
    val source: String,
) {
    /** 有没有出处。界面据此决定要不要画「—— 出处」那一行 */
    val hasSource: Boolean get() = source.isNotBlank()
}

/**
 * AI 成稿。永久留存于思考库——是资产不是消耗品。
 * 段落级 mine 用于「来源」视图：区分用户原话与 AI 补充。
 *
 * **它是独立资源。** [sourceNoteId] 只是「出自哪条记录」，不是父子关系：
 * 记录删了，这篇文章照样能读、能改、能转选题。
 * [sourceText] 是生成时的原文快照，让文章永远能回答「这段话是在说什么」。
 */
data class Article(
    val id: String,
    val sourceNoteId: String? = null,
    val sourceText: String = "",
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
 * 拍摄文案。**同样是独立资源。**
 *
 * 一篇文章可以拆出多个选题（不同角度），但选题拆好之后就自己活着：
 * 文章被删，已经拆好的分镜还能拿去拍。
 * [sourceTitle] 是文章标题快照，让选题脱离文章后仍能自报家门。
 */
data class Script(
    val id: String,
    val sourceArticleId: String? = null,
    val sourceTitle: String = "",
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