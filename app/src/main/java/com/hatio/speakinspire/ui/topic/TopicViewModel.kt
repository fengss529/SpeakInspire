package com.hatio.speakinspire.ui.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.model.Angle
import com.hatio.speakinspire.data.model.ArticleDraft
import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.Cue
import com.hatio.speakinspire.data.model.Script
import com.hatio.speakinspire.data.model.ScriptOutline
import com.hatio.speakinspire.data.model.Section
import com.hatio.speakinspire.data.remote.ErrorMapper
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.service.AiService
import com.hatio.speakinspire.service.ErrorKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 转选题页 ViewModel。
 *
 * ## 三步：拆角度 → 选一个 → 生成骨架
 *
 * 拆角度和生成骨架**是两次独立的调用**，不能合并：
 * 用户需要先看到「有哪几个角度可选」，才有办法判断。
 * 一次调用直接出骨架，等于剥夺了这个判断。
 *
 * ## 骨架是意群短语，不是逐字稿
 *
 * 用户组句困难是产品的核心假设。给逐字稿会让用户变成朗读机器，
 * 而朗读出来的短视频没有「他在讲自己的事」的感觉。
 *
 * 所以短语必须是完整意群：「我明明准备过」而不是「稿子」。
 */
@HiltViewModel
class TopicViewModel @Inject constructor(
    private val repo: IdeaRepository,
    private val ai: AiService,
) : ViewModel() {

    private val _state = MutableStateFlow(TopicUiState())
    val state: StateFlow<TopicUiState> = _state.asStateFlow()

    fun load(noteId: String) {
        viewModelScope.launch {
            val idea = repo.findIdea(noteId) ?: return@launch
            val existing = idea.scripts.firstOrNull { !it.parked }
            _state.value = TopicUiState(
                noteId = noteId,
                // 选题必须挂在**真实存在的**文章 id 上。
                // 之前这里是 articleId = "a_$noteId"，
                // 而文章的 id 是随机 UUID——外键对不上，
                // 要么插入时违反约束崩掉，要么选题挂到不存在的文章上，
                // 结果就是「存了选题但选题 tab 里没有」。
                articleId = idea.article?.id ?: "",
                articleTitle = idea.article?.title ?: idea.note.title,
                articleParagraphs = idea.article?.paragraphs?.map { it.text } ?: emptyList(),
                existingScript = existing,
                shotAt = existing?.shotAt,
            )
            // 还没有选题就去拆角度
            if (existing == null && idea.article != null) {
                deriveAngles(idea.article.title, idea.article.paragraphs.map { it.text })
            }
        }
    }

    /** 「再试一次」：重新读文章正文再拆角度，不能只拿标题去拆 */
    fun rederive() {
        viewModelScope.launch {
            val idea = repo.findIdea(_state.value.noteId) ?: return@launch
            val article = idea.article ?: return@launch
            deriveAngles(article.title, article.paragraphs.map { it.text })
        }
    }

    /** 第一步：拆角度。只要 3 个——选择时信息量刚好 */
    fun deriveAngles(title: String, paragraphs: List<String>) {
        _state.value = _state.value.copy(loadingAngles = true, errorMessage = null)
        viewModelScope.launch {
            val angles = try {
                ai.deriveAngles(ArticleDraft("n", title, title, paragraphs.map { ChatMessage.user(it) }))
            } catch (e: Exception) {
                emptyList()
            }
            _state.value = _state.value.copy(
                loadingAngles = false,
                angles = angles.take(3),
                errorMessage = if (angles.isEmpty()) {
                    ErrorMapper.humanize(ErrorKind.UNKNOWN)
                } else null,
            )
        }
    }

    /** 选中一个角度→ 生成骨架 */
    fun chooseAngle(angle: Angle) {
        val current = _state.value
        _state.value = current.copy(
            chosenAngle = angle,
            loadingOutline = true,
            errorMessage = null,
            step = TopicStep.OUTLINE,
        )
        viewModelScope.launch {
            val outline = try {
                ai.buildOutline(
                    // 文章正文必须带上。传 emptyList() 的时候，
                    // 模型只能凭一个角度名编骨架，实际结果是空的
                    ArticleDraft(
                        "n",
                        current.articleTitle,
                        current.articleTitle,
                        current.articleParagraphs.map { ChatMessage.user(it) },
                    ),
                    angle,
                )
            } catch (e: Exception) {
                ScriptOutline(angle.title, emptyList())
            }
            _state.value = _state.value.copy(
                loadingOutline = false,
                outline = outline,
            )
        }
    }

    /** 保存选题。可重复调用——同一篇文章可派生多个角度 */
    fun saveScript() {
        val current = _state.value
        val angle = current.chosenAngle ?: return
        val outline = current.outline ?: return
        if (outline.sections.isEmpty()) return
        // 没有文章就没有可挂的选题——不猜 id，直接不存
        if (current.articleId.isBlank()) return

        viewModelScope.launch {
            val script = Script(
                id = UUID.randomUUID().toString(),
                // 出处只是引用，没有外键：文章删了，这条分镜照样能拿去拍
                sourceArticleId = current.articleId,
                // 标题快照让选题脱离文章后仍能自报家门
                sourceTitle = current.articleTitle,
                angle = angle.title,
                sections = outline.sections.map { s ->
                    Section(
                        title = s.title,
                        seconds = s.seconds,
                        phrases = s.phrases,
                        cues = s.cues.map { Cue(it.icon, it.text) },
                    )
                },
                createdAt = System.currentTimeMillis(),
            )
            repo.saveScript(script)
            // 存完立刻切到「已保存」视图：
            // 停在原地不动会让用户以为没存上，再点一次就多出一条选题
            _state.value = _state.value.copy(
                existingScript = script,
                saved = true,
            )
        }
    }

    /**
     * 右滑 = 已拍摄。
     *
     * **这是个标记，不是动作。** 用户用手机里的其他 app 拍完之后回来点一下，
     * App 只负责记「这条已经拍过了」。
     *
     * 所以不能有「拍摄中」的中间态——那会让人以为 App 要负责拍。
     */
    fun markShot() {
        val scriptId = _state.value.existingScript?.id ?: return
        viewModelScope.launch {
            repo.markShot(scriptId)
            _state.value = _state.value.copy(shotAt = System.currentTimeMillis())
        }
    }

    fun undoShot() {
        val scriptId = _state.value.existingScript?.id ?: return
        viewModelScope.launch {
            repo.undoShot(scriptId)
            _state.value = _state.value.copy(shotAt = null)
        }
    }

    /** 左滑 = 这个角度先不拍，移回思考库。搁置不是删除 */
    fun park() {
        val scriptId = _state.value.existingScript?.id ?: return
        viewModelScope.launch {
            repo.parkScript(scriptId)
            _state.value = _state.value.copy(parked = true)
        }
    }

    fun back() {
        _state.value = _state.value.copy(
            chosenAngle = null,
            outline = null,
            step = TopicStep.ANGLES,
        )
    }
}

enum class TopicStep { ANGLES, OUTLINE }

data class TopicUiState(
    val noteId: String = "",
    /** 选题所属文章的真实 id。空表示这篇文章还没写成 */
    val articleId: String = "",
    val articleTitle: String = "",
    /**
     * 文章正文。
     *
     * 拆骨架必须用它——之前 `chooseAngle` 传的是空列表，
     * 模型只拿到一句「切入角度：xxx」，没有任何素材可拆，
     * 于是骨架页显示「共 0 段 · 大约 0 秒」。
     */
    val articleParagraphs: List<String> = emptyList(),
    val step: TopicStep = TopicStep.ANGLES,
    val angles: List<Angle> = emptyList(),
    val loadingAngles: Boolean = false,
    val chosenAngle: Angle? = null,
    val outline: ScriptOutline? = null,
    val loadingOutline: Boolean = false,
    /** 已存在的选题。从思考库进来时直接展示 */
    val existingScript: Script? = null,
    val shotAt: Long? = null,
    val parked: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasExisting: Boolean get() = existingScript != null

    /** 展示用：优先看已保存的，没有才看刚生成的 */
    val displaySections: List<Section>
        get() = existingScript?.sections
            ?: outline?.sections?.map { s ->
                Section(s.title, s.seconds, s.phrases, s.cues.map { Cue(it.icon, it.text) })
            }
            ?: emptyList()

    val displayAngle: String?
        get() = existingScript?.angle ?: chosenAngle?.title ?: outline?.angle

    val totalSeconds: Int get() = displaySections.sumOf { it.seconds }

    val isShot: Boolean get() = shotAt != null
}