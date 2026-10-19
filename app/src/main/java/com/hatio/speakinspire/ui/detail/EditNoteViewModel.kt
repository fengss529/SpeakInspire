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

    fun load(noteId: String) {
        // 只在首次进入时加载。
        // 没有这个守卫，用户刚打上两个字就会被数据库里的旧值覆盖——
        // 那比「不刷新」更糟。
        if (_state.value.noteId == noteId && !_state.value.loadFailed) return

        viewModelScope.launch {
            val idea = repo.findIdea(noteId) ?: run {
                // 记录不存在（被删了？）——明确置错误状态，
                // 不要静默停在空白页，用户会以为编辑器坏了
                _state.value = _state.value.copy(
                    noteId = noteId,
                    loadFailed = true,
                )
                return@launch
            }
            _state.value = _state.value.copy(
                // **noteId 必须在这里写进状态**。
                // 之前漏了它，save() 里 updateNote(current.noteId, ...)
                // 拿到的是空字符串，更新的是空 id ——于是「改了没变化」。
                noteId = noteId,
                title = idea.note.title,
                body = idea.note.body,
            )
        }
    }

    fun save(title: String, body: String, onDone: () -> Unit) {
        val current = _state.value
        if (current.saving) return
        if (current.noteId.isBlank()) {
            _state.value = current.copy(saveError = "记录加载失败，请返回重试")
            return
        }

        _state.value = current.copy(saving = true, saveError = null)
        viewModelScope.launch {
            val ok = repo.updateNote(current.noteId, title.trim(), body.trim())
            if (ok) {
                _state.value = _state.value.copy(saving = false)
                onDone()
            } else {
                // 更新没生效要让用户知道，不能假装成功
                _state.value = _state.value.copy(
                    saving = false,
                    saveError = "保存失败，请返回重试",
                )
            }
        }
    }
}

data class EditNoteUiState(
    val noteId: String = "",
    val title: String = "",
    val body: String = "",
    val saving: Boolean = false,
    /** 记录读不出来——显示明确提示而不是空白页 */
    val loadFailed: Boolean = false,
    val saveError: String? = null,
) {
    val canSave: Boolean
        get() = !saving && !loadFailed &&
            (title.isNotBlank() || body.isNotBlank())
}