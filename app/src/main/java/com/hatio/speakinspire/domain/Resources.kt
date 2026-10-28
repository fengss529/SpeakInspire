package com.hatio.speakinspire.domain

import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.Note
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.data.model.Sprout

/**
 * 三种资源，三个视图，三套状态。
 *
 * ## 为什么要拆掉原来那个 IdeaView
 *
 * 原来只有一个 `IdeaView(note, deepening, article, scripts, sprouts)`，
 * 它在一条链上算 8 个状态。那条链假设「记录 → 追问 → 文章 → 选题」是一条流水线，
 * 于是出现了这些别扭：
 *
 * - 选题左滑搁置，会把**文章**标成「先留着」
 * - 记录有没有被拍摄过，反过来改变**记录**显示什么（`STALE`）
 * - 「思考」tab 的条目本质是「有文章的 note」——文章没有自己的身份
 *
 * 现在它们是三种独立的资源：生成时从记录出发，生成之后各自存活。
 * 删掉一条记录，它的文章还在；删掉一篇文章，它的选题还在。
 *
 * ## 独立性指的是生命周期，不是禁止引用
 *
 * `Article.sourceNoteId` 和 `Script.sourceArticleId` 仍然在，但它们只是
 * **普通列，不是外键**（见 `Entities.kt`）。引用方自己负责处理「对方没了」：
 * `sourceText` / `sourceTitle` 是生成那一刻的快照，所以对方被删了也还读得懂。
 */

// ---------------------------------------------------------------- 记录

/**
 * 「记录」tab 的一条。
 *
 * 只持有**跟它同生共死**的附着物：追问（deepering）和发芽（sprout）都是
 * 在谈这条记录里那句话，记录没了它们也就没有意义，所以外键 CASCADE。
 *
 * 文章只持一个 id。文章已经是独立资源了，把整篇文章塞进来
 * 等于又把它变成记录的一部分。
 */
data class NoteView(
    val note: Note,
    val deepening: Deepening?,
    val sprouts: List<Sprout> = emptyList(),
    /** 由它生成的文章 id。只持 id，不持对象 */
    val articleId: String? = null,
    /** 从它派生出了新记录（「接着这句记一条」） */
    val hasChild: Boolean = false,
) {
    /** 最近一次发芽。详情页用它决定按钮文案是「发芽」还是「发芽 N」 */
    val latestSprout: Sprout? get() = sprouts.maxByOrNull { it.createdAt }

    val sproutCount: Int get() = sprouts.size

    val state: NoteState get() = NoteStateDeriver.derive(this)
}

/**
 * 记录的状态。**只描述记录自己**，不被选题的进度影响。
 *
 * 原来把 8 个状态混在一起，最难受的一条是 `STALE`：
 * 一条老记录显示「已过时」，居然取决于**新那条**有没有拍完。
 */
enum class NoteState(val label: String) {
    /** 记下了，没追问、没文章、没派生 */
    RECORD("记录"),

    /** 有未收尾的追问 */
    ONGOING("深化中"),

    /** 追问收尾了，但还没成文 */
    DEEPENED("已深化"),

    /** 已经有文章（文章独立存在，这里只是知道它存在） */
    COMPOSED("已成文"),

    /** 从它派生出了新记录 */
    DERIVED("已派生"),
}

object NoteStateDeriver {

    /**
     * 派生规则。顺序即优先级，从「有成果」到「还没开始」。
     *
     * `COMPOSED` 排在 `DERIVED` 前面：一条记录既成了文又派生过新记录时，
     * 「已成文」是更硬的事实——它说明这里产出过东西。
     * 派生只说明「从这里又长出一条」，不该盖住已有成果。
     */
    fun derive(v: NoteView): NoteState = when {
        v.articleId != null -> NoteState.COMPOSED
        v.deepening?.finishedAt != null -> NoteState.DEEPENED
        v.deepening != null -> NoteState.ONGOING
        v.hasChild -> NoteState.DERIVED
        else -> NoteState.RECORD
    }
}

// ---------------------------------------------------------------- 文章

/**
 * 「思考」tab 的一条。文章独立成条，**没有状态**。
 *
 * 原来文章头上挂着 `ARTICLED` / `PARKED` 两个状态，但 `PARKED`
 * 描述的是「这篇都不转了」——那是选题那边的决定。
 * 文章本身只有一件事可排：什么时候写的。
 */
data class ArticleView(
    val article: Article,
    /** 由它派生出的选题数。是查询结果，不是持有 */
    val topicCount: Int = 0,
    /**
     * 出处记录**现在是否还活着**。
     *
     * ⚠️ 不能拿 `article.sourceNoteId != null` 来判断。
     * `sourceNoteId` 是普通列（不是外键），记录被删掉之后它仍然是个
     * 非空字符串——那样写会让界面永远显示「出处还在」，而用户点过去是空的。
     *
     * 「有没有出处」是生成那一刻的快照，「出处还在不在」是当下的查询结果，
     * 两者必须分开存：前者决定界面要不要显示出处区块，后者决定那个区块可不可点。
     */
    val sourceNoteExists: Boolean = false,
) {
    /** 这篇文章当初是从一条记录生成来的（不管那条记录现在还在不在） */
    val hadSource: Boolean get() = article.sourceNoteId != null

    /** 出处记录还在，点得过去。false 时界面显示「原始记录已删除」，只给看快照 */
    val hasSource: Boolean get() = hadSource && sourceNoteExists

    val preview: String get() = article.paragraphs.firstOrNull()?.text.orEmpty()
}

// ---------------------------------------------------------------- 选题

/**
 * 「选题」tab 的一条。选题独立成条。
 *
 * 它记着生成时那篇文章的标题快照（`sourceTitle`），
 * 所以文章被删了，这一条仍然读得懂——「这条是给哪篇拍的」不会丢。
 */
data class TopicView(val script: Script) {
    val state: TopicState get() = TopicStateDeriver.derive(script)

    /** 卡片标题。文章标题快照优先，没有就退回角度 */
    val title: String get() = script.sourceTitle.ifBlank { script.angle }
}

enum class TopicState(val label: String) {
    TODO("待开拍"),
    SHOT("已拍摄"),
    PARKED("搁置"),
}

object TopicStateDeriver {
    /** 已拍摄优先于搁置：拍过了就是拍过了，后来搁置不改变这个事实 */
    fun derive(script: Script): TopicState = when {
        script.shotAt != null -> TopicState.SHOT
        script.parked -> TopicState.PARKED
        else -> TopicState.TODO
    }
}

// ---------------------------------------------------------------- 聚合

/**
 * 一次查出三张表的结果。UI 只读这个，不分别查各个表——
 * 避免多次查询之间状态不一致（比如正好在两次查询之间右滑了）。
 *
 * 它**不参与**任何状态派生，只是三个列表放在一起传，
 * 好处是界面只需要订阅一次。
 */
data class Library(
    val notes: List<NoteView> = emptyList(),
    val articles: List<ArticleView> = emptyList(),
    val topics: List<TopicView> = emptyList(),
) {
    val isEmpty: Boolean get() = notes.isEmpty() && articles.isEmpty() && topics.isEmpty()

    fun articleOf(note: NoteView): ArticleView? =
        note.articleId?.let { id -> articles.firstOrNull { it.article.id == id } }

    fun topicsOf(article: ArticleView): List<TopicView> =
        topics.filter { it.script.sourceArticleId == article.article.id }
}

/**
 * 三个列表各自的排序规则。
 *
 * 放在领域层而不是界面里：这些规则是产品判断，不是视图细节。
 * 让每个界面自己 `sortedBy` 迟早会漂移成三套不一样的顺序。
 */
object LibraryOrder {

    /** 记录：时间倒序。它是「我记过什么」的清单，不是待办队列 */
    fun notes(views: List<NoteView>): List<NoteView> =
        views.sortedByDescending { it.note.createdAt }

    /** 文章：成稿时间倒序。资产没有「先留着」这一说 */
    fun articles(views: List<ArticleView>): List<ArticleView> =
        views.sortedByDescending { it.article.writtenAt }

    /** 选题：待开拍在前，已拍摄次之，搁置沉底；同组按生成时间倒序 */
    fun topics(views: List<TopicView>): List<TopicView> =
        views.sortedWith(
            compareBy<TopicView> {
                when (it.state) {
                    TopicState.TODO -> 0
                    TopicState.SHOT -> 1
                    TopicState.PARKED -> 2
                }
            }.thenByDescending { it.script.createdAt }
        )
}

// ---------------------------------------------------------------- 连续天数

/**
 * 连续记录天数。纯函数，便于测试。
 *
 * ## 「今天还没记」不算断
 *
 * 从今天往回数，今天没有就从昨天起算。否则每天零点一到，
 * 一个记了 30 天的人会看到自己的连续天数变成 0——
 * 那会让这个数字变成一种指责，而不是记录。
 */
object Streak {

    fun days(timestamps: List<Long>, now: Long = System.currentTimeMillis()): Int {
        if (timestamps.isEmpty()) return 0
        val days = timestamps.map { dayKey(it) }.toSortedSet()
        var cursor = dayKey(now)
        // 今天还没记：从昨天起算，今天记了之后自动 +1
        if (cursor !in days) cursor = shift(cursor, -1)

        var count = 0
        while (cursor in days) {
            count++
            cursor = shift(cursor, -1)
        }
        return count
    }

    private fun dayKey(ms: Long): Int {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        return c.get(java.util.Calendar.YEAR) * 10000 +
            (c.get(java.util.Calendar.MONTH) + 1) * 100 +
            c.get(java.util.Calendar.DAY_OF_MONTH)
    }

    /** 用 Calendar 而不是自己减 86400000——夏令时和闰月会让那种算法出错 */
    private fun shift(key: Int, delta: Int): Int {
        val c = java.util.Calendar.getInstance()
        c.clear()
        c.set(key / 10000, (key / 100) % 100 - 1, key % 100)
        c.add(java.util.Calendar.DAY_OF_MONTH, delta)
        return c.get(java.util.Calendar.YEAR) * 10000 +
            (c.get(java.util.Calendar.MONTH) + 1) * 100 +
            c.get(java.util.Calendar.DAY_OF_MONTH)
    }
}

// ---------------------------------------------------------------- 手势

/**
 * 手势判定。纯函数——右滑标记已拍摄，左滑搁置。
 *
 * 决策：左滑不是删除（文章本身没错），是「这个角度先不拍」。
 */
enum class SwipeAction { MARK_SHOT, PARK, NONE }

object GestureJudge {
    const val MIN_DX = 55f
    /** 横向位移需明显大于纵向，否则判为滚动而非滑动 */
    const val HORIZONTAL_RATIO = 1.5f
    const val MAX_ANGLE_DEG = 22f

    fun judge(dx: Float, dy: Float): SwipeAction {
        if (kotlin.math.abs(dx) < MIN_DX) return SwipeAction.NONE
        if (kotlin.math.abs(dx) < kotlin.math.abs(dy) * HORIZONTAL_RATIO) return SwipeAction.NONE
        // 太斜的滑动手势多半是「斜着划一下」，不作为有效滑动
        val angle = Math.toDegrees(
            kotlin.math.atan(kotlin.math.abs(dy).toDouble() / kotlin.math.abs(dx).toDouble())
        )
        if (angle > MAX_ANGLE_DEG) return SwipeAction.NONE

        return if (dx > 0) SwipeAction.MARK_SHOT else SwipeAction.PARK
    }
}
