package com.hatio.speakinspire.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.model.Article
import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.ComposeResult
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.data.remote.ErrorMapper
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.service.AiService
import com.hatio.speakinspire.service.DeepeningContext
import com.hatio.speakinspire.service.ErrorKind
import com.hatio.speakinspire.service.TransportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 成文页 ViewModel。
 *
 * ## 三种结果，三种处理
 *
 * - **成功** → 展示文章，标注每段是「你说的」还是「AI 补」
 * - **拒稿**（素材不足）→ **不重试**。返回追问页而不是空文章——
 *   拒稿是产品在保护用户的内容质量，不能变成一次失败
 * - **失败**（网络/鉴权）→ 给可执行的话，允许重试
 *
 * ## 「来源」视图不是可选项
 *
 * 成文阶段允许 AI 补论证。如果不标注来源，用户无法验证哪些是自己的表达，
 * 那「允许补」就等于「可以编」。
 */
@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val repo: IdeaRepository,
    private val ai: AiService,
) : ViewModel() {

    private val _state = MutableStateFlow(ComposeUiState())
    val state: StateFlow<ComposeUiState> = _state.asStateFlow()

    fun load(noteId: String) {
        viewModelScope.launch {
            val idea = repo.findIdea(noteId) ?: return@launch
            _state.value = ComposeUiState(
                noteId = noteId,
                // 文章 id 必须跟库里的一致。
                //
                // 之前每次 load 都保留 UiState 默认的随机 UUID，
                // 于是「改完再存」会插入第二篇文章（一个 note 挂两篇），
                // 而选题表的 articleId 永远指向旧的那篇——
                // 表现就是「存了选题但选题 tab 里没有」。
                //
                // 没有文章时用 a_noteId 这个确定性的 id，
                // 重复保存就是 upsert，不会产生副本。
                articleId = idea.article?.id ?: "a_$noteId",
                title = idea.article?.title ?: "",
                paragraphs = idea.article?.paragraphs ?: emptyList(),
                alreadyComposed = idea.article != null,
                sourceMode = idea.article?.edited == true,
                originalNote = idea.note.body,
            )
        }
    }

    /** 调用 AI 成文 */
    fun compose() {
        val current = _state.value
        if (current.loading) return

        _state.value = current.copy(loading = true, errorMessage = null)

        viewModelScope.launch {
            val idea = repo.findIdea(current.noteId) ?: return@launch
            val rounds = idea.deepening?.rounds ?: emptyList()

            val ctx = DeepeningContext(
                noteId = current.noteId,
                originalNote = idea.note.body,
                rounds = rounds.toChatMessages(),
                roundIndex = rounds.count { it.answered },
                maxRounds = idea.deepening?.estimatedRounds ?: 5,
            )

            try {
                when (val result = ai.composeArticle(ctx)) {
                    is ComposeResult.Success -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            title = result.title,
                            paragraphs = result.paragraphs.map {
                                Paragraph(it.text, it.isMine)
                            },
                            rejected = null,
                        )
                    }

                    is ComposeResult.Rejected -> {
                        // 拒稿不是失败——是产品在保护内容。
                        // 直接告诉用户还差多少，让他决定要不要回去再聊聊。
                        _state.value = _state.value.copy(
                            loading = false,
                            rejected = RejectedInfo(
                                actualChars = result.actualChars,
                                requiredChars = result.requiredChars,
                            ),
                        )
                    }

                    is ComposeResult.Failed -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            errorMessage = ErrorMapper.humanize(
                                result.kind.toErrorKind()
                            ),
                        )
                    }
                }
            } catch (e: TransportException) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = ErrorMapper.humanize(e.kind),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = ErrorMapper.humanize(ErrorKind.NETWORK_UNAVAILABLE),
                )
            }
        }
    }

    /**
     * 保存文章。永久留存于思考库——它是资产不是消耗品。
     *
     * [onSaved] 在写库**之后**才调用。界面靠它跳转下一页——
     * 之前是先跳转再异步保存，下一页读库时文章还没落盘，
     * 于是转选题页看到的是「没有文章」，角度拆不出来。
     */
    fun save(onSaved: () -> Unit = {}) {
        val current = _state.value
        if (!current.canSave) return
        viewModelScope.launch {
            repo.saveArticle(
                Article(
                    id = current.articleId,
                    noteId = current.noteId,
                    title = current.title.trim(),
                    paragraphs = current.paragraphs,
                    writtenAt = System.currentTimeMillis(),
                    edited = true,
                )
            )
            _state.value = _state.value.copy(saved = true)
            onSaved()
        }
    }

    /** 切到「纯文章」视图——隐藏所有来源标注 */
    fun toggleSourceMode() {
        _state.value = _state.value.copy(sourceMode = !_state.value.sourceMode)
    }

    fun editTitle(v: String) {
        _state.value = _state.value.copy(title = v)
    }

    private fun List<Round>.toChatMessages(): List<ChatMessage> = buildList {
        this@toChatMessages.forEach { r ->
            r.question.takeIf { it.isNotBlank() }?.let { add(ChatMessage.ai(it)) }
            r.answer?.takeIf { it.isNotBlank() }?.let { add(ChatMessage.user(it)) }
        }
    }

    private fun com.hatio.speakinspire.data.model.ErrorKindLite.toErrorKind(): ErrorKind =
        when (this) {
            com.hatio.speakinspire.data.model.ErrorKindLite.NETWORK -> ErrorKind.NETWORK_UNAVAILABLE
            com.hatio.speakinspire.data.model.ErrorKindLite.UNAUTHORIZED -> ErrorKind.INVALID_API_KEY
            com.hatio.speakinspire.data.model.ErrorKindLite.RATE_LIMITED -> ErrorKind.RATE_LIMITED
            com.hatio.speakinspire.data.model.ErrorKindLite.UNKNOWN -> ErrorKind.UNKNOWN
        }
}

data class ComposeUiState(
    val noteId: String = "",
    /**
     * 文章 id。**不要用随机 UUID 做默认值**——
     * load() 必须显式给出与库里一致的 id，否则每次保存都会插一篇新文章。
     */
    val articleId: String = "",
    val title: String = "",
    val paragraphs: List<Paragraph> = emptyList(),
    val originalNote: String = "",
    val loading: Boolean = false,
    /** 非空表示 AI 拒稿了 */
    val rejected: RejectedInfo? = null,
    val errorMessage: String? = null,
    /** 已成文的文章 */
    val alreadyComposed: Boolean = false,
    /** true = 显示来源标注；false = 纯文章视图 */
    val sourceMode: Boolean = true,
    val saved: Boolean = false,
) {
    val hasContent: Boolean get() = title.isNotBlank() && paragraphs.isNotEmpty()

    val mineCount: Int get() = paragraphs.count { it.mine }
    val aiCount: Int get() = paragraphs.size - mineCount

    val canSave: Boolean get() = hasContent && !loading
}

data class RejectedInfo(
    val actualChars: Int,
    val requiredChars: Int,
) {
    val gap: Int get() = (requiredChars - actualChars).coerceAtLeast(0)

    /**
     * 拒稿的话术。
     *
     * 关键是**不让人觉得是自己的问题**——「你还没说够」会让人挫败，
     * 「可以先去多聊几轮」是给出路。
     */
    val message: String
        get() = "现在这些还不够写成一篇文章，再聊 $gap 个字左右就行。" +
            "不用凑字数——多说几句你当时是怎么想的就行。"
}