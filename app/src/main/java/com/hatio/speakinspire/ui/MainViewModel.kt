package com.hatio.speakinspire.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.local.TrashType
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.domain.ArticleView
import com.hatio.speakinspire.domain.NoteState
import com.hatio.speakinspire.domain.NoteView
import com.hatio.speakinspire.domain.Streak
import com.hatio.speakinspire.domain.TopicState
import com.hatio.speakinspire.domain.TopicView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 主界面的 ViewModel。
 *
 * 只暴露派生后的视图数据，**不持有任何状态字段**——
 * 状态全部来自领域层的派生函数，UI 里也不该出现 status 变量。
 *
 * 三种资源现在是三个独立列表，不再是「一条记录带着文章和选题」。
 * 这样每个 tab 增删自己的东西，不会牵动另外两个。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repo: IdeaRepository,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = repo.observeLibrary()
        .map { lib ->
            MainUiState(record = lib.notes, think = lib.articles, topic = lib.topics)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState(),
        )

    /**
     * 新建一条记录。
     *
     * [parentId] 非空表示这条是从某条记录**长**出来的——
     * 发芽报告里点「接着这句记一条」就走这条路。
     * 记下这层关系，将来才能回答「这个想法是从哪来的」。
     */
    fun createNote(
        title: String,
        body: String,
        parentId: String? = null,
        onDone: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            val id = repo.createNote(title, body, parentId = parentId)
            onDone(id)
        }
    }

    /**
     * 右滑 = 标记已拍摄。
     *
     * 现在选题自己就是一张能独立打开的卡片，所以直接拿 scriptId 操作——
     * 不再需要「先找到那条记录，再从它的选题里挑一个没拍的」。
     */
    fun markShot(scriptId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val exists = repo.findTopic(scriptId) != null
            if (exists) repo.markShot(scriptId)
            onResult(exists)
        }
    }

    fun undoShot(scriptId: String) {
        viewModelScope.launch { repo.undoShot(scriptId) }
    }

    /** 左滑 = 搁置。搁置而非删除 */
    fun park(scriptId: String) {
        viewModelScope.launch { repo.parkScript(scriptId) }
    }

    fun unpark(scriptId: String) {
        viewModelScope.launch { repo.unparkScript(scriptId) }
    }

    fun moveToTrash(type: String, resourceId: String) {
        viewModelScope.launch { repo.moveToTrash(type, resourceId) }
    }

    fun moveNoteToTrash(noteId: String) = moveToTrash(TrashType.NOTE, noteId)

    fun moveArticleToTrash(articleId: String) = moveToTrash(TrashType.ARTICLE, articleId)

    fun moveTopicToTrash(scriptId: String) = moveToTrash(TrashType.SCRIPT, scriptId)

    fun restore(type: String, resourceId: String) {
        viewModelScope.launch { repo.restoreFromTrash(type, resourceId) }
    }
}

/**
 * 主界面状态。
 *
 * 注意三个列表都是派生结果——没有任何「status」字段，
 * 因为状态是算出来的，存下来反而会不一致。
 */
data class MainUiState(
    /** 「记录」tab */
    val record: List<NoteView> = emptyList(),
    /** 「思考」tab */
    val think: List<ArticleView> = emptyList(),
    /** 「选题」tab */
    val topic: List<TopicView> = emptyList(),
) {
    val isEmpty: Boolean get() = record.isEmpty() && think.isEmpty() && topic.isEmpty()

    /** 统计页的数字，全部实时算出 */
    val stats: Stats get() = Stats(
        recorded = record.size,
        deepened = record.count { it.state in DEEPENED_STATES },
        composed = record.count { it.state == NoteState.COMPOSED },
        articles = think.size,
        articlesWithTopic = think.count { it.topicCount > 0 },
        topics = topic.size,
        todo = topic.count { it.state == TopicState.TODO },
        shot = topic.count { it.state == TopicState.SHOT },
        parked = topic.count { it.state == TopicState.PARKED },
        streak = Streak.days(record.map { it.note.createdAt }),
    )

    /** 「已深化但还没成文」——这一环流失最多，单独标出来 */
    val pendingCount: Int get() = record.count { it.state == NoteState.DEEPENED }

    private companion object {
        val DEEPENED_STATES = setOf(NoteState.DEEPENED, NoteState.COMPOSED)
    }
}

/**
 * 统计口径。**不再有跨资源的漏斗**。
 *
 * 原来那张漏斗拿「记录总数」当分母算「已拍摄比例」，
 * 在三个资源独立之后就没有意义了：一条记录可能压根没成文，
 * 但它的文章早就变成了选题。跨资源做除法只会算出一个谁也不知道
 * 该怎么解释的数。
 *
 * 现在每一项都只描述自己那一类。
 */
data class Stats(
    /** 记录总数 */
    val recorded: Int,
    /** 记录里追问收尾过的（含已成文） */
    val deepened: Int,
    /** 记录里已经有文章的 */
    val composed: Int,
    /** 文章总数 */
    val articles: Int,
    /** 文章里已经转过选题的篇数 */
    val articlesWithTopic: Int,
    /** 选题总数 */
    val topics: Int,
    val todo: Int,
    val shot: Int,
    val parked: Int,
    /** 连续记录天数 */
    val streak: Int,
) {
    /**
     * 记录 → 成文的转化。分母是记录，分子是记录自己的状态，口径自洽。
     *
     * **分母为 0 时返回 null，不是 0**。
     * `0 / 0` 算成 0% 是在说一件不成立的事——一条记录都没有的时候
     * 「成文比例 0%」会让人以为「我写了很多但一篇都没成」。
     */
    val composeRate: Int? get() = if (recorded == 0) null else composed * 100 / recorded

    /** 选题里的拍摄进度。分母是选题，不掺记录 */
    val shotRate: Int? get() = if (topics == 0) null else shot * 100 / topics
}
