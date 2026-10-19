package com.hatio.speakinspire.ui.deepening

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.local.SimpleJson
import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.service.AiService
import com.hatio.speakinspire.service.DeepeningContext
import com.hatio.speakinspire.service.ErrorKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 追问对话页的 ViewModel。
 *
 * ## 三个关键的状态规则
 *
 * **1. 进度点用户发送后才点亮**
 * 不是 AI 问完时点亮——否则用户会觉得「只要 AI 问完就算我答过了」，
 * 从而敷衍作答。点亮 = 用户真的答了。
 *
 * **2. 折叠摘要显示用户原话**
 * 不是 AI 的总结。AI 总结会把用户的话再加工一遍，
 * 让他怀疑「我说的是这个意思吗」。原话最可信。
 *
 * **3. 收尾卡不含任何 AI 总结**
 * 只有「你说过的话」+「成文 / 先留着」两个动作。
 * 让 AI 在这里总结等于撤销了整个追问阶段建立的信任。
 */
@HiltViewModel
class DeepeningViewModel @Inject constructor(
    private val repo: IdeaRepository,
    private val ai: AiService,
) : ViewModel() {

    private val _state = MutableStateFlow(DeepeningUiState())
    val state: StateFlow<DeepeningUiState> = _state.asStateFlow()

    private var streamJob: Job? = null

    /**
     * 进入追问页。
     *
     * **第一次进来就自动发起追问。**
     *
     * 之前只加载数据不发起请求，页面就停在空白状态——
     * 用户看到的是「说说你的看法」输入框，不知道该输入还是该等 AI 问，
     * 于是卡住不动（真机实测反馈的问题之一）。
     *
     * 自动发起之后，用户看到的是 AI 的第一个问题，
     * 自然就知道「哦，它要问我东西」——意图不用解释。
     *
     * 已经聊过几轮的会话则不重复发起，
     * 否则会浪费一次调用并让历史出现重复的问题。
     */
    fun load(noteId: String) {
        viewModelScope.launch {
            val idea = repo.findIdea(noteId) ?: return@launch
            val deepening = idea.deepening
            val rounds = deepening?.rounds ?: emptyList()

            _state.value = DeepeningUiState(
                noteId = noteId,
                noteTitle = idea.note.title,
                noteBody = idea.note.body,
                rounds = rounds,
                finished = deepening?.finishedAt != null,
                answeredCount = rounds.count { it.answered },
                maxRounds = deepening?.estimatedRounds ?: DEFAULT_MAX_ROUNDS,
                errorMessage = null,
            )

            // 会话已收尾（有文章或用户主动结束）就不再自动问
            if (deepening?.finishedAt != null) return@launch

            // 最后一轮已经问出来但用户还没答 → 不重复问，
            // 直接聚焦输入框让用户回答。
            //
            // 这一步之前写错了：条件写成「最后一轮有问题就跳过」，
            // 结果「深化中」的记录（有历史轮次、未回答）会既不提问、
            // 也不聚焦，用户看到的就是一个不能动的页面——
            // 真机实测反馈的「点击 AI 追问卡住」就是这个。
            val lastAnswered = rounds.lastOrNull()?.answered == true
            val lastQuestionBlank = rounds.lastOrNull()?.question.isNullOrBlank()
            if (lastQuestionBlank) {
                // 最后一轮只有问题没有内容（异常状态），重新问一次
                askFirstQuestion()
            } else if (!lastAnswered) {
                // 正常状态：等用户回答。让输入框自动获得焦点，
                // 用户落地就能直接打字，不用先点一下输入框
                _state.value = _state.value.copy(pendingAnswer = true)
            } else {
                // 全部答完还没收尾，继续问下一个
                askFirstQuestion()
            }
        }
    }

    /**
     * 用户主动重试。
     *
     * 卡住时必须给一条出路——否则用户只能退出，
     * 而他甚至不知道是网络问题还是 App 坏了。
     */
    fun retryFirstQuestion() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = false, errorMessage = null)
            askFirstQuestion()
        }
    }

    /** 发起第一轮追问。不经过 send()，因为用户此刻还没说话 */
    private suspend fun askFirstQuestion() {
        val current = _state.value
        _state.value = current.copy(busy = true, errorMessage = null)

        val ctx = DeepeningContext(
            noteId = current.noteId,
            originalNote = current.noteBody,
            rounds = emptyList(),
            roundIndex = 0,
            maxRounds = current.maxRounds,
        )

        try {
            ai.streamQuestion(ctx).collect { chunk ->
                _state.value = _state.value.copy(
                    streaming = _state.value.streaming + chunk,
                )
            }

            val text = _state.value.streaming
            _state.value = _state.value.copy(
                rounds = if (text.isBlank()) {
                    _state.value.rounds
                } else {
                    _state.value.rounds + Round(question = text, answer = null)
                },
                streaming = "",
                busy = false,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                busy = false,
                errorMessage = com.hatio.speakinspire.data.remote.ErrorMapper.humanize(
                    (e as? com.hatio.speakinspire.service.TransportException)?.kind
                        ?: com.hatio.speakinspire.service.ErrorKind.NETWORK_UNAVAILABLE
                ),
            )
        }
    }

    /**
     * 发给AI，接收流式返回。
     *
     * 先把用户这句话落到 rounds 里——**进度点在这一刻点亮**。
     */
    fun send(text: String) {
        val userText = text.trim()
        if (userText.isEmpty() || _state.value.busy) return

        val current = _state.value
        val nextRounds = current.rounds + Round(question = current.streaming, answer = userText)

        _state.value = current.copy(
            rounds = nextRounds,
            streaming = "",
            busy = true,
            // 关键：用户发送即点亮，不是等 AI 问完
            answeredCount = current.answeredCount + 1,
            errorMessage = null,
        )

        streamJob = viewModelScope.launch {
            val ctx = DeepeningContext(
                noteId = current.noteId,
                originalNote = current.noteBody,
                // AI 侧只需要对话消息，历史问题由前端维护
                rounds = flattenForAi(nextRounds),
                roundIndex = nextRounds.count { it.answered } - 1,
                maxRounds = current.maxRounds,
            )
            persist(current.noteId, nextRounds)

            ai.streamQuestion(ctx)
                .catch { e ->
                    val kind = (e as? com.hatio.speakinspire.service.TransportException)?.kind
                        ?: ErrorKind.NETWORK_UNAVAILABLE
                    _state.value = _state.value.copy(
                        busy = false,
                        errorMessage = com.hatio.speakinspire.data.remote.ErrorMapper.humanize(kind),
                    )
                }
                .collect { chunk ->
                    _state.value = _state.value.copy(
                        streaming = _state.value.streaming + chunk,
                    )
                }

            // 流结束：把这段 AI 提问固化到最后一轮
            val finalRounds = if (_state.value.streaming.isNotBlank()) {
                _state.value.rounds.dropLast(1) +
                    Round(question = _state.value.streaming, answer = null)
            } else {
                _state.value.rounds
            }
            _state.value = _state.value.copy(
                rounds = finalRounds,
                streaming = "",
                busy = false,
            )
            persist(current.noteId, finalRounds)
        }
    }

    /** 点气泡跳过打字机——直接把剩余内容一次性显示 */
    fun skipTyping() {
        val current = _state.value
        if (current.streaming.isBlank()) return
        val next = current.rounds.dropLast(1) + Round(question = current.streaming, answer = null)
        streamJob?.cancel()
        streamJob = null
        _state.value = current.copy(rounds = next, streaming = "", busy = false)
        viewModelScope.launch { persist(current.noteId, next) }
    }

    /** 提前收尾。用户不想聊了但保留已说的 */
    fun finishEarly() {
        val current = _state.value
        if (current.streaming.isNotBlank()) skipTyping()
        viewModelScope.launch {
            persist(current.noteId, _state.value.rounds, finished = true)
            _state.value = _state.value.copy(finished = true)
        }
    }

    /** 成文。交给成文阶段处理 */
    fun compose() {
        val current = _state.value
        viewModelScope.launch {
            val deepening = Deepening(
                id = UUID.randomUUID().toString(),
                noteId = current.noteId,
                rounds = current.rounds,
                startedAt = System.currentTimeMillis(),
                finishedAt = System.currentTimeMillis(),
                estimatedRounds = current.maxRounds,
            )
            repo.saveDeepening(deepening)
            _state.value = _state.value.copy(finished = true, composing = true)
        }
    }

    private suspend fun persist(noteId: String, rounds: List<Round>, finished: Boolean = false) {
        val current = _state.value
        if (rounds.none { it.answered }) return
        repo.saveDeepening(
            Deepening(
                id = "d_$noteId",
                noteId = noteId,
                rounds = rounds,
                startedAt = System.currentTimeMillis(),
                finishedAt = if (finished) System.currentTimeMillis() else null,
                estimatedRounds = current.maxRounds,
            )
        )
    }

    /**
     * 把 Round 列表拍平成 ChatMessage 序列。
     *
     * 顺序很关键：AI 问 → 我答 → AI 问 → 我答……
     * 如果顺序错了，模型会把我的答案当成问题来回应。
     */
    private fun flattenForAi(rounds: List<Round>): List<ChatMessage> = buildList {
        rounds.forEach { r ->
            r.question.takeIf { it.isNotBlank() }?.let { add(ChatMessage.ai(it)) }
            r.answer?.takeIf { it.isNotBlank() }?.let { add(ChatMessage.user(it)) }
        }
    }

    companion object {
        const val DEFAULT_MAX_ROUNDS = 5
    }
}

data class DeepeningUiState(
    val noteId: String = "",
    val noteTitle: String = "",
    val noteBody: String = "",
    val rounds: List<Round> = emptyList(),
    /** 正在流式返回的文本。空串表示不在流式中 */
    val streaming: String = "",
    /** 已点亮的进度点数 */
    val answeredCount: Int = 0,
    val maxRounds: Int = 5,
    val busy: Boolean = false,
    val finished: Boolean = false,
    val composing: Boolean = false,
    val errorMessage: String? = null,
    /**
     * 有问题在等用户回答，且还没聚焦过输入框。
     * UI 层用它决定要不要自动弹键盘。
     */
    val pendingAnswer: Boolean = false,
) {
    /** 已完成的问答对数 */
    val completedRounds: Int get() = rounds.count { it.answered }

    /** 最后一轮之后不再显示新的 AI 气泡（有正在流的也算） */
    val showTypingBubble: Boolean get() = streaming.isNotEmpty()

    /** 折叠后每条显示什么——用户原话，不是 AI 总结 */
    fun summaryOf(round: Round): String =
        round.answer?.takeIf { it.isNotBlank() } ?: "（未作答）"
}