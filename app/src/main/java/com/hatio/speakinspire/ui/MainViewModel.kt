package com.hatio.speakinspire.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
            record = IdeaStateDeriver.deepeningQueue(ideas),
            think = library,
            topic = topics,
            allIdeas = ideas,
            recordBadge = IdeaStateDeriver.deepeningQueue(ideas).size,
            topicBadge = topics.count {
                IdeaStateDeriver.derive(it) == IdeaState.TOPIC
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    fun createNote(title: String, body: String, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            val id = repo.createNote(title, body)
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

    fun forkFrom(noteId: String, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            val newId = repo.forkFrom(noteId)
            if (newId != null) onDone(newId)
        }
    }

    fun moveToTrash(noteId: String) {
        viewModelScope.launch { repo.moveToTrash(noteId) }
    }

    fun restore(noteId: String) {
        viewModelScope.launch { repo.restoreFromTrash(noteId) }
    }
}

/**
 * 主界面状态。
 *
 * 注意所有列表都是派生结果——没有任何「status」字段，
 * 因为状态是算出来的，存下来反而会不一致。
 */
data class MainUiState(
    /** 「记录」tab：待深化的想法，按时间正序 */
    val record: List<IdeaView> = emptyList(),
    /** 「思考」tab：已成文章的作品，适合拍的排前面 */
    val think: List<IdeaView> = emptyList(),
    /** 「选题」tab：待开拍 + 已拍摄 */
    val topic: List<IdeaView> = emptyList(),
    /** 全部想法，供统计页用 */
    val allIdeas: List<IdeaView> = emptyList(),
    val recordBadge: Int = 0,
    val topicBadge: Int = 0,
) {
    val isEmpty: Boolean get() = allIdeas.isEmpty()

    /** 统计页的四个数字，全部实时算出 */
    val stats: Stats get() = Stats(
        recorded = allIdeas.size,
        deepened = allIdeas.count {
            IdeaStateDeriver.derive(it) in setOf(
                IdeaState.DEEPENED, IdeaState.ARTICLED,
                IdeaState.TOPIC, IdeaState.SHOT, IdeaState.PARKED,
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