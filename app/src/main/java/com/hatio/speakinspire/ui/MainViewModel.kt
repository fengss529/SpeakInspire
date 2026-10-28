package com.hatio.speakinspire.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.local.TrashType
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.domain.IdeaState
import com.hatio.speakinspire.domain.IdeaStateDeriver
import com.hatio.speakinspire.domain.IdeaView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 主界面的 ViewModel。
 *
 * 只暴露派生后的视图数据，**不持有任何状态字段**——
 * 8 个状态全部来自 [IdeaStateDeriver]，UI 里也不该出现status 变量。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repo: IdeaRepository,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = combine(
        repo.observeIdeas(),
        repo.observeLibrary(),
        repo.observeTopics(),
    ) { ideas, library, topics ->
        MainUiState(
            // 记录 tab 显示全部想法——它是「我记过的东西」的清单，
            // 不是流水线的一环。深化完成后不该从这里消失。
            record = IdeaStateDeriver.recordTimeline(ideas),
            think = library,
            topic = topics,
            allIdeas = ideas,
        )
    }.stateIn(
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
     * 返回是否成功——UI 据此决定要不要弹撤销条。
     */
    fun markShot(noteId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val idea = repo.findIdea(noteId)
            val script = idea?.scripts?.firstOrNull { !it.parked && it.shotAt == null }
            if (script == null) {
                onResult(false)
            } else {
                repo.markShot(script.id)
                onResult(true)
            }
        }
    }

    fun undoShot(noteId: String) {
        viewModelScope.launch {
            val idea = repo.findIdea(noteId) ?: return@launch
            idea.scripts.firstOrNull { it.shotAt != null }?.let { repo.undoShot(it.id) }
        }
    }

    /** 左滑 = 移回思考库。搁置而非删除 */
    fun park(noteId: String) {
        viewModelScope.launch {
            val idea = repo.findIdea(noteId) ?: return@launch
            idea.scripts.firstOrNull { !it.parked }?.let { repo.parkScript(it.id) }
        }
    }

    fun moveToTrash(noteId: String) {
        viewModelScope.launch { repo.moveToTrash(TrashType.NOTE, noteId) }
    }

    fun restore(noteId: String) {
        viewModelScope.launch { repo.restoreFromTrash(TrashType.NOTE, noteId) }
    }
}

/**
 * 主界面状态。
 *
 * 注意所有列表都是派生结果——没有任何「status」字段，
 * 因为状态是算出来的，存下来反而会不一致。
 */
data class MainUiState(
    /** 「记录」tab：**全部**记录，按时间倒序（不是待办队列） */
    val record: List<IdeaView> = emptyList(),
    /** 「思考」tab：已成文章的作品，适合拍的排前面 */
    val think: List<IdeaView> = emptyList(),
    /** 「选题」tab：待开拍 + 已拍摄 */
    val topic: List<IdeaView> = emptyList(),
    /** 全部想法，供统计页用 */
    val allIdeas: List<IdeaView> = emptyList(),
) {
    val isEmpty: Boolean get() = allIdeas.isEmpty()

    /** 统计页的四个数字，全部实时算出 */
    val stats: Stats get() = Stats(
        recorded = allIdeas.size,
        deepened = allIdeas.count {
            IdeaStateDeriver.derive(it) in setOf(
                IdeaState.DEEPENED, IdeaState.ARTICLED,
                IdeaState.TOPIC, IdeaState.SHOT, IdeaState.PARKED,
                // 已过时 = 以这条为起点新建、新那条拍完了。
                // 它同样走完了深化，漏掉会让「深化率」偏低
                IdeaState.STALE,
            )
        },
        articlEd = allIdeas.count { it.article != null },
        shot = allIdeas.count {
            IdeaStateDeriver.derive(it) == IdeaState.SHOT
        },
    )
}

data class Stats(
    val recorded: Int,
    val deepened: Int,
    val articlEd: Int,
    val shot: Int,
) {
    val deepenRate: Int get() = if (recorded == 0) 0 else deepened * 100 / recorded
    val articleRate: Int get() = if (recorded == 0) 0 else articlEd * 100 / recorded
    val shotRate: Int get() = if (recorded == 0) 0 else shot * 100 / recorded
}