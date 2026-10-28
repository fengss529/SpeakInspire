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
            val view = repo.findNote(noteId) ?: return@launch
            val fmt = SimpleDateFormat("yyyy 年 M 月 d 日 HH:mm", Locale.CHINA)
            _state.value = NoteDetailUiState(
                noteId = noteId,
                title = view.note.title,
                body = view.note.body,
                createdLabel = fmt.format(Date(view.note.createdAt)),
                rounds = view.deepening?.rounds ?: emptyList(),
                // 文章是独立资源，这里只知道「我生成过一篇」，
                // 它可能已经被单独删了——所以只记 id，不假装它一定在
                hasArticle = view.articleId != null,
                articleId = view.articleId,
                sproutCount = view.sproutCount,
                latestSproutTitle = view.latestSprout?.title.orEmpty(),
            )
        }
    }

    /**
     * 把这一条发出去（连同追问、文章、发芽、选题）→ 系统分享面板。
     *
     * 和设置页的「导出全部」分工明确：那边是留一份备份到自己手里，
     * 这边是一次性把某一条交给别人。所以这边走分享面板，
     * 那边写进 `下载/随手记/`。
     *
     * 失败时把话说明白：用户点了一下什么都没有，会以为图标坏了。
     */
    fun shareNote() {
        val current = _state.value
        if (current.noteId.isBlank()) return
        viewModelScope.launch {
            // 单条分享也要读出它派生出去的文章与选题，
            // 所以仍然走全库快照——少维护一条「只取一条」的查询路径
            val library = repo.exportSnapshot()
            val md = MarkdownExporter.note(library, current.noteId)
            val name = MarkdownExporter.fileName(current.title.ifBlank { "随手记" })
            val ok = MarkdownShare.share(context, name, md)
            _state.value = _state.value.copy(
                exportMessage = if (ok) null else "没能生成分享文件，再试一次。",
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
    /** 由这条记录生成的文章 id。文章已被单独删掉时为 null */
    val articleId: String? = null,
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
