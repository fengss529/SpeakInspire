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
import com.hatio.speakinspire.data.repository.DraftStyleStore
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.service.AiService
import com.hatio.speakinspire.service.DeepeningContext
import com.hatio.speakinspire.service.DraftStyle
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
    private val styleStore: DraftStyleStore,
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
            val rounds = note.deepening?.rounds.orEmpty()
            val sprouts = note.sprouts
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
                roundCount = rounds.count { it.answered },
                sproutCount = sprouts.size,
                sproutTitles = sprouts.map { it.title },
                // 已有文章时按它当时用的风格显示；
                // 没有文章就按设置里的默认值
                style = article?.styleId?.let { DraftStyle.byIdOrNull(it) } ?: styleStore.current(),
                // 正文是库里那篇，风格就是它记的那个。没记过（v5 之前）留空，
                // 界面据此不把默认风格冒充成「这篇的风格」
                composedStyle = article?.styleId?.let { DraftStyle.byIdOrNull(it) },
                styleRecorded = article?.styleId?.isNotBlank() == true,
                styleInstruction = styleStore.noteOf(),
                // 没有的素材不能勾。一个勾了却什么都没变的选项，
                // 会让用户以为这功能坏了
                includeDialogue = rounds.any { it.answered },
                includeSprouts = sprouts.isNotEmpty(),
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
                style = DraftStyle.byIdOrNull(view.article.styleId) ?: styleStore.current(),
                composedStyle = DraftStyle.byIdOrNull(view.article.styleId),
                styleRecorded = view.article.styleId.isNotBlank(),
                styleInstruction = styleStore.noteOf(),
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
                // 三个开关和风格都在这里定死。
                //
                // 服务实现不读存储、不读界面——它只认这份上下文。
                // 这样「界面勾了什么 / 设置里选了什么 → 请求里就是什么」
                // 能被链路测试直接断言，不会出现「看起来换了风格，
                // 发出去还是老提示词」这种事。
                sprouts = note.sprouts,
                includeNote = current.includeNote,
                includeDialogue = current.includeDialogue,
                includeSprouts = current.includeSprouts,
                style = current.style,
                styleInstruction = current.styleInstruction,
            )

            try {
                when (val result = ai.composeArticle(ctx)) {
                    is ComposeResult.Success -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            title = result.title,
                            paragraphs = result.paragraphs.map {
                                Paragraph(it.text, it.isMine, it.fromSprout)
                            },
                            rejected = null,
                            needsRecompose = false,
                            // 刚写出来的这篇，风格是确定的
                            styleRecorded = true,
                            composedStyle = current.style,
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
                    // 记下这篇是按哪种风格写的。详情页那句「这是篇观点文」
                    // 只可能从这儿来——正文里读不出来
                    styleId = current.style.id,
                )
            )
            _state.value = _state.value.copy(saved = true, alreadyComposed = true)
            onSaved()
        }
    }

    /**
     * 把这一篇发出去：单条 md → 系统分享面板。
     *
     * ## 和设置页那个「导出全部」不是一回事
     *
     * 导出是**备份**：一次把全部内容写进 `下载/随手记/`，用户自己去那儿找。
     * 这里是**分享**：一次一篇，交给别人。
     * 两个动作的心智完全不同，所以入口也不在一处——
     * 混在一起的话，用户想发一篇出去，得先去设置页导出全部。
     *
     * 只对**已经存进思考库**的文章可用：内存里刚生成、还没落盘的文章
     * 分享出去会是一份空文件，用户会以为坏了。
     */
    fun shareArticle() {
        val current = _state.value
        if (current.articleId.isBlank()) return
        viewModelScope.launch {
            // 分享一篇要连它的选题一起带走，所以读一次全库。
            // 数据量在千条以内，这点开销比多维护一条只读文章与选题的查询路径便宜。
            val library = repo.exportSnapshot()
            val md = MarkdownExporter.article(library, current.articleId)
            val name = MarkdownExporter.fileName(current.title.ifBlank { "文章" })
            val ok = MarkdownShare.share(context, name, md)
            if (!ok) {
                _state.value = _state.value.copy(errorMessage = "没能生成分享文件，再试一次。")
            }
        }
    }

    /** 切到「纯文章」视图——隐藏所有来源标注 */
    fun toggleSourceMode() {
        _state.value = _state.value.copy(sourceMode = !_state.value.sourceMode)
    }

    // ------------------------------------------------------------ 风格与素材开关

    /**
     * 换风格。
     *
     * **只对这一次生效，不写回设置。**
     *
     * 写回的话，用户想试一下「口播稿」看看什么样，回来时默认风格
     * 已经被改了，下次写别的还留在口播上——而他根本没做这个决定。
     * 设置页那个选择是「以后都这样」，这里是「这次想这么写」，
     * 两个动作的心智不一样，不能共用一个存储。
     */
    fun selectStyle(style: DraftStyle) {
        val s = _state.value
        if (s.style == style) return
        _state.value = s.copy(style = style).markStale()
    }

    /**
     * 勾掉/勾上一块素材。
     *
     * 三个芯片默认全选，理由是「综合记录、回答、发芽」这个说法本身——
     * 留开关是因为芽里混着别人的话，有人不希望它们进自己的文章。
     *
     * 不允许把三项全关：那时候模型手里什么都没有，
     * 它只能凭空写一篇。留最后一项勾着，比让用户看到一篇凭空写的文章好。
     */
    fun toggleChip(chip: ComposeChip) {
        val s = _state.value
        val next = when (chip) {
            ComposeChip.NOTE -> s.copy(includeNote = !s.includeNote)
            ComposeChip.DIALOGUE -> s.copy(includeDialogue = !s.includeDialogue)
            ComposeChip.SPROUTS -> s.copy(includeSprouts = !s.includeSprouts)
        }
        val noneLeft = !next.includeNote && !next.includeDialogue && !next.includeSprouts
        if (noneLeft) return
        _state.value = next.markStale()
    }

    /**
     * 改过风格或勾选之后，已有正文就不再对应现在的设置了。
     *
     * 这里**不自动重新生成**：一次成文是一次真实调用，
     * 用户点一下芯片就烧一次额度、等十几秒，那是很坏的体验。
     * 只把「该重写了」标出来，按钮换成「按新的选择重写」，
     * 决定权给他。
     */
    private fun ComposeUiState.markStale(): ComposeUiState =
        if (hasContent) copy(needsRecompose = true) else this

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
    /** 这一篇写哪种风格。成文页的 chip 显示它，点开可以当次换掉 */
    val style: DraftStyle = DraftStyle.DEFAULT,
    /**
     * 库里**确实记过**这篇的风格。
     *
     * v5 之前的文章 `styleId` 是空串，[style] 只能回落到设置里的默认值——
     * 那个默认值是「以后怎么写」，不是「这篇当时怎么写」。
     * 拿它去说「这是篇随感」就是编的，所以界面要看这个标记决定说不说。
     */
    val styleRecorded: Boolean = false,
    /** 设置里那段补充写作要求。成文页只显示、不编辑（那是设置页的事） */
    val styleInstruction: String = "",
    /** 三个素材开关。默认全选 */
    val includeNote: Boolean = true,
    val includeDialogue: Boolean = true,
    val includeSprouts: Boolean = true,
    /** 这条记录答过的轮数。0 表示「追问」这个芯片不该出现 */
    val roundCount: Int = 0,
    /** 这条记录长过几颗芽。0 表示「发芽」这个芯片不该出现 */
    val sproutCount: Int = 0,
    val sproutTitles: List<String> = emptyList(),
    /**
     * 改过风格或勾选之后，正文和当前设置已经对不上了。
     *
     * 只标出来，不自动重写——一次成文是一次真实调用，
     * 点一下芯片就烧一次额度、等十几秒，那是很坏的体验。
     */
    val needsRecompose: Boolean = false,
    /**
     * 正文这一版**实际**是用哪种风格写的。
     *
     * 和 [style] 不是一回事：改过风格 chip 但还没点重写时，[style] 已经是新的，
     * 正文还是旧的。保存栏那句「按「X」写的」说的是**正文**，
     * 拿 [style] 去说就会出现「上面写着这篇还是按刚才的设置写的，
     * 下面写着按「随感」写的」这种自相矛盾的两句话。
     *
     * null 表示正文来历不明（v5 之前的文章没记 styleId）。
     */
    val composedStyle: DraftStyle? = null,
) {
    /** 保存栏该说「按哪种写的」——说的是正文，不是 chip 上那个 */
    val writtenStyle: DraftStyle get() = composedStyle ?: style
    val hasContent: Boolean get() = title.isNotBlank() && paragraphs.isNotEmpty()

    val mineCount: Int get() = paragraphs.count { it.mine }
    /** 纯 AI 补的论证（不含来自芽的那些） */
    val aiCount: Int get() = paragraphs.count { !it.mine && !it.fromSprout }
    /** 用到芽里的说法/例子/引用的段落 */
    val sproutParaCount: Int get() = paragraphs.count { it.fromSprout }

    val canSave: Boolean get() = hasContent && !loading

    /** 追问芯片该不该出现 */
    val hasDialogue: Boolean get() = roundCount > 0
    /** 发芽芯片该不该出现 */
    val hasSprouts: Boolean get() = sproutCount > 0
}

/** 成文页顶部那三个可勾的素材来源 */
enum class ComposeChip { NOTE, DIALOGUE, SPROUTS }

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