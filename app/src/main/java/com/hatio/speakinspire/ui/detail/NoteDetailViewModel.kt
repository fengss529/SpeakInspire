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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * 这页本身不做 AI 调用，只负责「看清 + 改 + 决定下一步」。
 *
 * ## 从「只读 + 编辑按钮」改成「点哪编辑哪」
 *
 * 原来这页是只读的，改一个字要先点「编辑」跳到另一个页面。
 * 问题不在多一步，在于**跳走之后就看不见上下文**：想改第三轮的回答，
 * 得先记住自己当时在回答什么，再在那个只有正文的编辑页里改。
 *
 * 现在标题、正文、每一条回答各自可编辑，改完自动存。
 * 没有「编辑态」这个概念，也没有保存按钮——保存不是用户的活。
 *
 * ## 自动保存的三条护栏
 *
 * 1. **防抖 600ms**：每敲一个字都写库会让主线程后面的查询排队，
 *    打字会一顿一顿的。
 * 2. **离页立刻 flush**：防抖窗口里退出页面，那半秒的字不能丢。
 * 3. **失败要说**：库里没写进去而界面看着像存了，是最坏的一种沉默。
 */
@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    private val repo: IdeaRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(NoteDetailUiState())
    val state: StateFlow<NoteDetailUiState> = _state.asStateFlow()

    private var noteJob: Job? = null
    private val answerJobs = mutableMapOf<Int, Job>()

    /** 有没落盘的内容。load() 前要先看它，否则会用库里的旧值盖掉刚打的字 */
    private var noteDirty = false
    private val dirtyAnswers = mutableSetOf<Int>()

    fun load(noteId: String) {
        viewModelScope.launch {
            flushNow()

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

    // ------------------------------------------------------------ 就地编辑

    fun editTitle(text: String) {
        _state.value = _state.value.copy(title = text)
        noteDirty = true
        scheduleNoteSave()
    }

    fun editBody(text: String) {
        _state.value = _state.value.copy(body = text)
        noteDirty = true
        scheduleNoteSave()
    }

    /**
     * 改第 [index] 轮的回答。
     *
     * 只替换 answer，不动 question、不动轮数——
     * 问答配对是这条链路里最容易被搞坏的东西。
     */
    fun editAnswer(index: Int, text: String) {
        val current = _state.value
        if (index !in current.rounds.indices) return

        val rounds = current.rounds.toMutableList()
        rounds[index] = rounds[index].copy(answer = text.ifBlank { null })
        _state.value = current.copy(
            rounds = rounds,
            // 文章是按「改之前那些回答」写的，改完就不再对得上。
            // 这个提示只在本次会话里有效——持久化要等到能给
            // deepening 记 updatedAt 的那一版（DB v5）
            answersEdited = current.answersEdited || current.hasArticle,
        )

        dirtyAnswers += index
        answerJobs[index]?.cancel()
        answerJobs[index] = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            writeAnswer(index)
        }
    }

    private fun scheduleNoteSave() {
        noteJob?.cancel()
        noteJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            writeNote()
        }
    }

    private suspend fun writeNote() {
        val s = _state.value
        if (s.noteId.isBlank() || !noteDirty) return
        val ok = repo.updateNote(s.noteId, s.title.trim(), s.body)
        if (ok) {
            noteDirty = false
            if (_state.value.saveError != null) {
                _state.value = _state.value.copy(saveError = null)
            }
        } else {
            _state.value = _state.value.copy(saveError = "改动没存上，检查一下记录还在不在。")
        }
    }

    private suspend fun writeAnswer(index: Int) {
        val s = _state.value
        if (s.noteId.isBlank() || index !in s.rounds.indices) return
        val ok = repo.updateRoundAnswer(s.noteId, index, s.rounds[index].answer.orEmpty())
        if (ok) {
            dirtyAnswers -= index
        } else {
            _state.value = _state.value.copy(saveError = "这一轮的改动没存上。")
        }
    }

    /**
     * 把防抖窗口里还没写的内容立刻写掉。
     *
     * 离开页面时调用。不做这一步，最后一个 600ms 里打的字会跟着
     * ViewModel 一起消失，而界面上刚刚明明显示过它。
     */
    fun flushPending() {
        viewModelScope.launch { flushNow() }
    }

    private suspend fun flushNow() {
        noteJob?.cancel()
        noteJob = null
        if (noteDirty) writeNote()

        val pending = dirtyAnswers.toList()
        answerJobs.values.forEach { it.cancel() }
        answerJobs.clear()
        pending.forEach { writeAnswer(it) }
    }

    fun dismissSaveError() {
        _state.value = _state.value.copy(saveError = null)
    }

    /**
     * 把这一条发出去（连同追问、文章、发芽、选题）→ 系统分享面板。
     *
     * 和设置页的「导出全部」分工明确：那边是留一份备份到自己手里，
     * 这边是一次性把某一条交给别人。
     */
    fun shareNote() {
        val current = _state.value
        if (current.noteId.isBlank()) return
        viewModelScope.launch {
            // 分享前先把没落盘的写掉，否则分享出去的是旧内容
            flushNow()
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

    private companion object {
        const val AUTOSAVE_DEBOUNCE_MS = 600L
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
    /** 自动保存失败。为 null 时不显示任何东西——保存成功不该打扰用户 */
    val saveError: String? = null,
    /** 这次会话里改过回答。用来提示「文章和现在的回答对不上了」 */
    val answersEdited: Boolean = false,
) {
    /** 有过追问就不该再显示「AI 追问」以外的文案 */
    val answeredRounds: Int get() = rounds.count { it.answered }

    /**
     * 有没有「可以接着聊」的对话。
     *
     * 判据是**至少一轮有内容**，不是 rounds 非空——
     * rounds 里可能有 AI 问了但没写完的空轮次。
     */
    val hasDeepened: Boolean
        get() = rounds.any { it.question.isNotBlank() }

    /** 追问按钮的文案：聊过就是「继续聊」，没聊过是从零开始 */
    val deepenLabel: String get() = if (hasDeepened) "继续聊" else "AI 追问"

    val hasSprout: Boolean get() = sproutCount > 0

    /** 发芽按钮文案：长过就邀请他再看/再长，没长过就是发起 */
    val sproutLabel: String get() = if (hasSprout) "发芽 ${sproutCount}" else "发芽"

    /** 文章比回答旧了。要提示，但不要拦着他写 */
    val articleOutdated: Boolean get() = hasArticle && answersEdited
}
