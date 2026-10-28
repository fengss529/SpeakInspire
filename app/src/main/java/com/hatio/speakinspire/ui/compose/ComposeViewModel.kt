package com.hatio.speakinspire.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.export.MarkdownExporter
import com.hatio.speakinspire.data.export.MarkdownShare
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context

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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ComposeUiState())
    val state: StateFlow<ComposeUiState> = _state.asStateFlow()

    /**
     * 进入成文页。
     *
     * [autoCompose] 为 true 时，读完数据**立刻**发起成文。
     *
     * 之前这个动作放在界面的 `LaunchedEffect` 里：
     * ```
     * cvm.load(id); if (!cstate.hasContent) cvm.compose()
     * ```
     * 但 `load` 是异步的，`compose()` 读到的 noteId 还是空串 →
     * `findIdea("")` 返回 null → 静默 return。
     * 表现是成文页停在「还没开始 / 开始整理」，等用户再点一次。
     *
     * 顺序必须在这里保证：load 完成 → noteId 就位 → 再成文。
     */
    fun load(noteId: String, autoCompose: Boolean = false) {
        viewModelScope.launch {
            val note = repo.findNote(noteId) ?: return@launch
            // 文章和记录是两条独立资源，所以要分两次读。
            // 多一次查询换来的是：文章没了，记录仍然打得开。
            val article = repo.findArticleOfNote(noteId)?.article
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
                articleId = article?.id ?: "a_$noteId",
                title = article?.title ?: "",
                paragraphs = article?.paragraphs ?: emptyList(),
                alreadyComposed = article != null,
                sourceMode = article?.edited == true,
                originalNote = note.note.body,
            )
            // 已有文章就不重复生成——那是用户的资产，不该被覆盖
            if (autoCompose && article == null) compose()
        }
    }

    /**
     * 直接按文章 id 打开。从「思考」tab 进来走这条。
     *
     * 文章独立之后它可能没有任何来源记录（记录被删了），
     * 所以不能靠 noteId 反查——那样这类文章就永远打不开了。
     */
    fun loadArticle(articleId: String) {
        viewModelScope.launch {
            val view = repo.findArticle(articleId) ?: return@launch
            _state.value = ComposeUiState(
                // 出处记录**已经不在了**就把 noteId 留空，界面据此关掉「回去再聊」。
                //
                // 不能直接写 `sourceNoteId.orEmpty()`：那是普通列，记录被删之后
                // 它还是非空的，于是按钮亮着，点进去是一条空白的追问页。
                noteId = if (view.hasSource) view.article.sourceNoteId.orEmpty() else "",
                articleId = view.article.id,
                title = view.article.title,
                paragraphs = view.article.paragraphs,
                alreadyComposed = true,
                sourceMode = true,
                // 原文快照：来源记录删了，这里仍然能回答「这段话从哪儿来」
                originalNote = view.article.sourceText,
            )
        }
    }

    /** 调用 AI 成文 */
    fun compose() {
        val current = _state.value
        if (current.loading) return
        // noteId 为空说明数据还没读完。这时发请求只会查不到东西然后静默退出，
        // 用户看到的是一句「开始整理」——看起来像按钮没生效。
        if (current.noteId.isBlank()) {
            _state.value = current.copy(errorMessage = "还没读到这条记录，稍等一下再点。")
            return
        }

        _state.value = current.copy(loading = true, errorMessage = null)

        viewModelScope.launch {
            val note = repo.findNote(current.noteId) ?: return@launch
            val rounds = note.deepening?.rounds ?: emptyList()

            val ctx = DeepeningContext(
                noteId = current.noteId,
                originalNote = note.note.body,
                rounds = rounds.toChatMessages(),
                roundIndex = rounds.count { it.answered },
                maxRounds = note.deepening?.estimatedRounds ?: 5,
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
                                result.kind.toErrorKind(),
                                result.detail,
                            ),
                        )
                    }
                }
            } catch (e: TransportException) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = ErrorMapper.humanize(e.kind, e.detail),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = ErrorMapper.humanize(ErrorKind.NETWORK_UNAVAILABLE, e.message),
                )
            }
        }
    }

    /**
     * 保存文章。永久留存于思考库——它是资产不是消耗品。
     *
     * ## 存完不跳转
     *
     * 之前保存完会自动跳到转选题页，默认「存文章就是为了拍视频」。
     * 但很多文章就是文章，存下来是为了留着、为了改、为了导出。
     * 存完之后留在原地，把下一步的选择权交出去：
     * 转选题、继续聊、导出，或者直接返回。
     *
     * [onSaved] 在写库**之后**才调用——界面刷新要靠它。
     */
    fun save(onSaved: () -> Unit = {}) {
        val current = _state.value
        if (!current.canSave) return
        viewModelScope.launch {
            repo.saveArticle(
                Article(
                    id = current.articleId,
                    // 记录 id 只是出处，没有外键。记录被删掉后这里会悬空，文章照常存在
                    sourceNoteId = current.noteId.ifBlank { null },
                    // 生成那一刻的原文快照——原始记录删了，文章里「这段在说什么」还得有据可查
                    sourceText = current.originalNote,
                    title = current.title.trim(),
                    paragraphs = current.paragraphs,
                    writtenAt = System.currentTimeMillis(),
                    edited = true,
                )
            )
            _state.value = _state.value.copy(saved = true, alreadyComposed = true)
            onSaved()
        }
    }

    /**
     * 把这篇（连同它的追问、发芽、选题）导出成 Markdown。
     *
     * 只对**已经存进思考库**的文章可用：内存里刚生成、还没落盘的文章
     * 走导出会拿到空内容，用户会以为导出坏了。
     */
    fun exportMarkdown() {
        val current = _state.value
        if (current.articleId.isBlank()) return
        viewModelScope.launch {
            // 导出一篇文章要连它的选题一起带走，所以读一次全库。
            // 数据量在千条以内，这点开销比多维护一条只读文章与选题的查询路径便宜。
            val library = repo.exportSnapshot()
            val md = MarkdownExporter.article(library, current.articleId)
            val name = MarkdownExporter.fileName(current.title.ifBlank { "文章" })
            val ok = MarkdownShare.share(context, name, md)
            if (!ok) {
                _state.value = _state.value.copy(errorMessage = "没能生成导出文件，再试一次。")
            }
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