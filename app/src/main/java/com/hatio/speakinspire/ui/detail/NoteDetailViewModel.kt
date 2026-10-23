package com.hatio.speakinspire.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.export.MarkdownExporter
import com.hatio.speakinspire.data.export.MarkdownShare
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.data.repository.IdeaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
 *
 * ## 「下一步」不是一个链条，是几个并列的入口
 *
 * 这个页面曾经只有一条路：编辑 → 追问 → 成文 → 转选题 →
 * 标记已拍。五个环节首尾相接，断在哪儿都走不下去。
 *
 * 现在它是**这条记录的控制台**：追问、发芽、看文章、导出，
 * 随时可以选一个，也可以谁都不选就退出去。
 * 记录本来就该在那里躺着，不该是一条必须走完的生产线。
 */
@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    private val repo: IdeaRepository,
    @ApplicationContext private val context: Context,
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
                sproutCount = idea.sproutCount,
                latestSproutTitle = idea.latestSprout?.title.orEmpty(),
            )
        }
    }

    /**
     * 导出这一条（连同追问、文章、发芽、选题）为 Markdown。
     *
     * 失败时把话说明白：用户点了一下什么都没有，会以为按钮坏了。
     */
    fun exportMarkdown() {
        val noteId = _state.value.noteId
        if (noteId.isBlank()) return
        viewModelScope.launch {
            val idea = repo.findIdea(noteId) ?: return@launch
            val md = MarkdownExporter.note(idea)
            val name = MarkdownExporter.fileName(idea.note.title.ifBlank { "随手记" })
            val ok = MarkdownShare.share(context, name, md)
            _state.value = _state.value.copy(
                exportMessage = if (ok) null else "没能生成导出文件，再试一次。",
            )
        }
    }

    fun consumeExportMessage() {
        _state.value = _state.value.copy(exportMessage = null)
    }
}

data class NoteDetailUiState(
    val noteId: String = "",
    val title: String = "",
    val body: String = "",
    val createdLabel: String = "",
    val rounds: List<Round> = emptyList(),
    val hasArticle: Boolean = false,
    /** 这条记录发过几次芽 */
    val sproutCount: Int = 0,
    val latestSproutTitle: String = "",
    val exportMessage: String? = null,
) {
    /** 有过追问就不该再显示「AI 追问」以外的文案 */
    val answeredRounds: Int get() = rounds.count { it.answered }

    /**
     * 有没有「可以接着聊」的对话。
     *
     * 判据是**至少一轮有内容**，不是 rounds 非空——
     * rounds 里可能有 AI 问了但没写完的空轮次，
     * 那种情况说「继续追问」会误导用户以为之前聊过。
     */
    val hasDeepened: Boolean
        get() = rounds.any { it.question.isNotBlank() }

    /**
     * 追问按钮的文案。
     *
     * 「AI 追问」和「继续追问」的区别是**用户能不能预期**：
     * 继续追问意味着之前聊过、这次接着聊；AI 追问意味着从零开始。
     * 文案与实际状态不一致就会让用户点进去发现不对。
     */
    val deepenLabel: String get() = if (hasDeepened) "继续追问" else "AI 追问"

    val hasSprout: Boolean get() = sproutCount > 0

    /** 发芽按钮文案：长过就邀请他再看/再长，没长过就是发起 */
    val sproutLabel: String get() = if (hasSprout) "发芽 ${sproutCount}" else "发芽"
}
