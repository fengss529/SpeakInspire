package com.hatio.speakinspire.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.data.repository.IdeaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 记录条详情页状态。
 *
 * 这页本身不做 AI 调用，只负责「看清 + 决定下一步」。
 */
@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    private val repo: IdeaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NoteDetailUiState())
    val state: StateFlow<NoteDetailUiState> = _state.asStateFlow()

    fun load(noteId: String) {
        viewModelScope.launch {
            val idea = repo.findIdea(noteId) ?: return@launch
            val fmt = SimpleDateFormat("yyyy 年 M 月 d 日 HH:mm", Locale.CHINA)
            _state.value = NoteDetailUiState(
                noteId = noteId,
                title = idea.note.title,
                body = idea.note.body,
                createdLabel = fmt.format(Date(idea.note.createdAt)),
                rounds = idea.deepening?.rounds ?: emptyList(),
                hasArticle = idea.article != null,
            )
        }
    }
}

data class NoteDetailUiState(
    val noteId: String = "",
    val title: String = "",
    val body: String = "",
    val createdLabel: String = "",
    val rounds: List<Round> = emptyList(),
    val hasArticle: Boolean = false,
) {
    /** 有过追问就不该再显示「AI 追问」以外的文案 */
    val hasDeepened: Boolean get() = rounds.isNotEmpty()

    val answeredRounds: Int get() = rounds.count { it.answered }

    /** 追问按钮的文案——继续追问比重新开始更准确 */
    val deepenLabel: String get() = if (hasDeepened) "继续追问" else "AI 追问"
}