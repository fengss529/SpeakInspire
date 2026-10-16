package com.hatio.speakinspire.domain

import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.Note
import com.hatio.speakinspire.data.model.Script

/**
 * 8 个派生状态。**note 表不存 status**，全部由关联计算得出。
 *
 * 这是纯函数，无数据库、无 Android 依赖——最容易测，也最容易错。
 */
enum class IdeaState(val label: String) {
    /** 刚记下，还没开始追问 */
    WAIT("待深化"),

    /** 有未完成的追问会话 */
    ONGOING("深化中"),

    /** 追问完成但用户选了「暂不成文」，或 AI 以素材不足拒稿 */
    DEEPENED("已深化"),

    /** 已有文章 */
    ARTICLED("已成文章"),

    /** 已有文章，但文章在「先留着」标签里 */
    PARKED("先留着"),

    /** 派生出待拍选题 */
    TOPIC("待开拍"),

    /** 选题已右滑标记 */
    SHOT("已拍摄"),

    /** 以旧笔记为起点新建的旧记录 */
    STALE("已过时")
}

/**
 * 派生快照——把一条笔记关联的所有数据聚成一个对象，
 * UI 只读这个，不直接碰三张表。
 */
data class IdeaView(
    val note: Note,
    val deepening: Deepening?,
    val article: Article?,
    val scripts: List<Script>
)

object IdeaStateDeriver {

    /** 派生规则。顺序即优先级，从强约束到弱约束。 */
    fun derive(v: IdeaView): IdeaState {
        // 数据完整性防御：脚本必然挂在某篇文章下。
        // 若出现「有脚本但无文章」，说明外键被破坏——
        // 此时不能把它当成正常的「待开拍」，否则 UI 会点进一个打不开的选题。
        if (v.article == null && v.scripts.isNotEmpty()) return IdeaState.WAIT

        val live = v.scripts.filter { !it.parked }
        val shot = live.any { it.shotAt != null }
        val hasTopic = live.isNotEmpty()

        return when {
            // 以旧笔记为起点新建 → 原文被判为过时
            v.note.parentId != null && shot -> IdeaState.STALE

            // 已拍摄：任一未搁置选题带拍摄时间
            shot -> IdeaState.SHOT

            // 待开拍：已转选题但未拍
            hasTopic -> IdeaState.TOPIC

            // 先留着：文章仍在，但全部选题都被移回思考库
            v.article != null && v.scripts.isNotEmpty() -> IdeaState.PARKED

            // 深化中：有会话但未收尾
            v.deepening != null && v.deepening.finishedAt == null -> IdeaState.ONGOING

            // 追问完成但未成文（含 AI 拒稿情形）
            v.deepening?.finishedAt != null && v.article == null -> IdeaState.DEEPENED

            // 已有文章：默认「适合拍」
            v.article != null -> IdeaState.ARTICLED

            else -> IdeaState.WAIT
        }
    }

    /** 待深化列表用——给「深化」tab 排序，越久没碰的越靠前 */
    fun deepeningQueue(views: List<IdeaView>): List<IdeaView> =
        views.filter { derive(it) == IdeaState.WAIT }
            .sortedBy { it.note.createdAt }

    /** 思考库用——适合拍的排前面，「先留着」沉底，同组按成稿时间倒序 */
    fun libraryOrder(views: List<IdeaView>): List<IdeaView> =
        views.filter { it.article != null }
            .sortedWith(
                // false(PARKED) < true，所以升序即「非搁置在前」
                compareBy<IdeaView> { derive(it) == IdeaState.PARKED }
                    .thenByDescending { it.article?.writtenAt ?: 0L }
            )

    /** 选题库用——待开拍在前，已拍摄沉底 */
    fun topicOrder(views: List<IdeaView>): List<IdeaView> =
        views.filter { it.article != null && it.scripts.any { s -> !s.parked } }
            .sortedBy { v ->
                val live = v.scripts.filterNot { it.parked }
                if (live.any { it.shotAt != null }) 1 else 0
            }
}

/**
 * 手势判定。纯函数——右滑标记已拍摄，左滑移回思考库。
 * 决策：左滑不是删除（文章本身没错），是「这个角度先搁置」。
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