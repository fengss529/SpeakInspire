package com.hatio.speakinspire.ui.sprout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.model.Sprout
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.service.AiService
import com.hatio.speakinspire.service.SproutContext
import com.hatio.speakinspire.service.SproutResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 发芽页的 ViewModel。
 *
 * ## 为什么不自动发芽
 *
 * 追问页进来就自动问第一个问题，因为「深化」是那条路的主线动作。
 * 发芽不是——它是一次**主动的「我想知道别人怎么想」**。
 * 自动触发会让用户以为这又是流程的下一步，于是每次记完都被推着发芽，
 * 而发芽的价值恰恰在于「我自己想让它长的时候它才长」。
 *
 * 所以进来只读旧芽。新芽必须由用户按一下。
 */
@HiltViewModel
class SproutViewModel @Inject constructor(
    private val repo: IdeaRepository,
    private val ai: AiService,
) : ViewModel() {

    private val _state = MutableStateFlow(SproutUiState())
    val state: StateFlow<SproutUiState> = _state.asStateFlow()

    /**
     * 追问里他说过的话。
     *
     * 不放进 UiState：界面用不到，而它动辄上千字，
     * 塞进 StateFlow 会让每一帧重组都白跑一遍比较。
     */
    private var answers: List<String> = emptyList()

    fun load(noteId: String) {
        viewModelScope.launch {
            val idea = repo.findNote(noteId) ?: return@launch
            answers = idea.deepening?.rounds
                ?.filter { it.answered }
                ?.mapNotNull { it.answer?.takeIf { a -> a.isNotBlank() } }
                ?: emptyList()

            _state.value = SproutUiState(
                noteId = noteId,
                noteTitle = idea.note.title,
                noteBody = idea.note.body,
                existing = idea.sprouts.sortedByDescending { it.createdAt },
                // 有旧芽就先摆出来：用户点进来多半是想再看一眼上次那份
                current = idea.latestSprout,
                loading = false,
            )
        }
    }

    /** 发芽一次。失败时把原因带回来，不留一句「没长出来」 */
    fun sprout() {
        val s = _state.value
        if (s.busy || s.noteId.isBlank()) return

        viewModelScope.launch {
            _state.value = s.copy(busy = true, error = null)
            val result = ai.sprout(
                SproutContext(
                    noteId = s.noteId,
                    noteTitle = s.noteTitle,
                    noteBody = s.noteBody,
                    answers = answers,
                    // 把长过的芽的标题传下去，避免第二次给同一批连接
                    previousTitles = s.existing.map { it.title },
                )
            )

            when (result) {
                is SproutResult.Success -> {
                    val now = System.currentTimeMillis()
                    val sprout = Sprout(
                        // 带时间戳：发芽是追加，不是覆盖。
                        // 用固定 id 会把上一次的报告顶掉，等于删了用户的认知记录
                        id = "sp_${s.noteId}_$now",
                        noteId = s.noteId,
                        title = result.title,
                        sections = result.sections,
                        createdAt = now,
                    )
                    repo.saveSprout(sprout)
                    _state.value = _state.value.copy(
                        busy = false,
                        current = sprout,
                        existing = listOf(sprout) + _state.value.existing,
                        error = null,
                    )
                }

                is SproutResult.Failed -> _state.value = _state.value.copy(
                    busy = false,
                    error = result.detail ?: "没长出来。再试一次，或者去设置里看看模型配置。",
                )
            }
        }
    }

    /** 翻看历史某一份芽 */
    fun review(sprout: Sprout) {
        _state.value = _state.value.copy(current = sprout)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}

data class SproutUiState(
    val noteId: String = "",
    val noteTitle: String = "",
    val noteBody: String = "",
    /** 这条记录历次发芽，最新在前 */
    val existing: List<Sprout> = emptyList(),
    /** 当前正在看的那一份。null = 还没发过芽 */
    val current: Sprout? = null,
    /** 首次读库完成前为 true，避免「还没开始」闪一下 */
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val hasReport: Boolean get() = current != null

    /** 以前还长过别的芽（除了当前这份） */
    val olderCount: Int get() = (existing.size - if (current != null) 1 else 0).coerceAtLeast(0)
}
