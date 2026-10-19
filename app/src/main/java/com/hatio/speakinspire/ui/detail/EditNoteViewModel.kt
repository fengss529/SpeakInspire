package com.hatio.speakinspire.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.repository.IdeaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 编辑已有记录。
 *
 * ## 为什么需要
 *
 * 随手记常常只写了半句就存了，回头补是常态。
 * 不给编辑就等于强迫用户重新记一条——
 * 而「重新记」会打断原来的时间线，让思考变得零散。
 */
@HiltViewModel
class EditNoteViewModel @Inject constructor(
    private val repo: IdeaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EditNoteUiState())
    val state: StateFlow<EditNoteUiState> = _state.asStateFlow()

    private var loaded = false

    fun load(noteId: String) {
        // 重组时会重复调用，只加载一次，
        // 否则用户在输入的内容会被数据库里的旧值覆盖
        if (loaded) return
        loaded = true

        viewModelScope.launch {
            val idea = repo.findIdea(noteId) ?: return@launch
            _state.value = _state.value.copy(
                title = idea.note.title,
                body = idea.note.body,
            )
        }
    }

    fun save(title: String, body: String, onDone: () -> Unit) {
        val current = _state.value
        if (current.saving) return

        _state.value = current.copy(saving = true)
        viewModelScope.launch {
            repo.updateNote(current.noteId, title.trim(), body.trim())
            _state.value = _state.value.copy(saving = false)
            onDone()
        }
    }
}

data class EditNoteUiState(
    val noteId: String = "",
    val title: String = "",
    val body: String = "",
    val saving: Boolean = false,
) {
    val canSave: Boolean get() = !saving && (title.isNotBlank() || body.isNotBlank())
}