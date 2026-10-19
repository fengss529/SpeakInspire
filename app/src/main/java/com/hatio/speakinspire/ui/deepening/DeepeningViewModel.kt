package com.hatio.speakinspire.ui.deepening

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hatio.speakinspire.data.model.ChatMessage
import com.hatio.speakinspire.data.model.Deepening
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.data.repository.IdeaRepository
import com.hatio.speakinspire.data.remote.ErrorMapper
import com.hatio.speakinspire.service.AiService
import com.hatio.speakinspire.service.DeepeningContext
import com.hatio.speakinspire.service.ErrorKind
import com.hatio.speakinspire.service.TransportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 *
 * ## Round 的语义（曾经写错过，务必看清）
 *
 * 一个 [Round] = **一个问题 + 可选的一个回答**。
 *
 * - AI 问完 → `rounds + Round(问题, null)`（未答）
 * - 用户回答 → **把 answer 填进最后一轮**（不是新增一轮）
 * - AI 再问 → `rounds + Round(新问题, null)`
 *
 * 之前这里是「用户回答时新增一轮空问题的 Round，然后在下一问到达时
 * `dropLast(1)` 把那一轮删掉」——于是**每答一次就把刚说的那句话丢掉**。
 * 表现是：折叠区永远空白、进度点永远亮不起来、成文时素材为 0。
 * 这一条由 `DeepeningFlowTest` 守着。
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
     * 用户看到的是输入框，不知道该输入还是该等 AI 问，于是卡住不动。
     *
     * 已经聊过几轮的会话则不重复发起，否则会浪费一次调用并让历史出现重复的问题。
     */
    fun load(noteId: String) {
        viewModelScope.launch {
            val idea = repo.findIdea(noteId) ?: return@launch
            val deepening = idea.deepening
            var rounds = deepening?.rounds ?: emptyList()

            // 最后一轮是「空问题」的残渣（旧版本 bug 留下的数据），
            // 读进来先清掉，否则会在库里越积越多
            rounds = rounds.dropLastWhile { it.question.isBlank() && !it.answered }

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

            // 会话已收尾（已成文或用户主动结束）就不再自动问
            if (deepening?.finishedAt != null) return@launch
            if (rounds.count { it.answered } >= _state.value.maxRounds) return@launch

            val last = rounds.lastOrNull()
            when {
                // 全新会话 → 问第一个
                last == null -> askQuestion(rounds)

                // 有问题等着回答 → 不重复问，聚焦输入框
                !last.answered -> {
                    _state.value = _state.value.copy(focusTick = 1)
                }

                // 全部答完还没收尾 → 继续问下一个（带上历史，别丢上下文）
                else -> askQuestion(rounds)
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
        val current = _state.value
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = false, errorMessage = null)
            if (current.awaitingAnswer) {
                // 已经有一个待答的问题，重试不该再问一个——只需重新聚焦
                _state.value = _state.value.copy(focusTick = current.focusTick + 1)
            } else {
                askQuestion(_state.value.rounds)
            }
        }
    }

    /**
     * 问下一个问题。
     *
     * [history] 是发给模型的上下文：必须带上已经答过的轮次，
     * 否则模型会从零开始问——用户会觉得自己前面说的白说了。
     */
    private suspend fun askQuestion(history: List<Round>) {
        val current = _state.value
        _state.value = current.copy(busy = true, errorMessage = null)

        val ctx = DeepeningContext(
            noteId = current.noteId,
            originalNote = current.noteBody,
            rounds = flattenForAi(history),
            roundIndex = history.count { it.answered },
            maxRounds = current.maxRounds,
        )

        try {
            ai.streamQuestion(ctx).collect { chunk ->
                _state.value = _state.value.copy(streaming = _state.value.streaming + chunk)
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                busy = false,
                errorMessage = humanize(e),
            )
            return
        }

        val text = _state.value.streaming
        val appended = if (text.isBlank()) {
            _state.value.rounds
        } else {
            _state.value.rounds + Round(question = text, answer = null)
        }
        _state.value = _state.value.copy(
            rounds = appended,
            streaming = "",
            busy = false,
            focusTick = if (text.isBlank()) _state.value.focusTick else _state.value.focusTick + 1,
        )
        // 只有答过话的会话才落库——否则库里会堆一堆没人搭理的问题
        if (text.isNotBlank()) persist(current.noteId, appended)
    }

    /**
     * 用户发送回答。
     *
     * **回答落在最后一轮上**，然后才去要下一个问题。
     * 进度点在这一刻点亮，不等 AI 回复。
     */
    fun send(text: String) {
        val userText = text.trim()
        if (userText.isEmpty() || _state.value.busy) return

        val current = _state.value
        if (!current.awaitingAnswer) return

        val nextRounds = current.rounds.dropLast(1) +
            current.rounds.last().copy(answer = userText)

        _state.value = current.copy(
            rounds = nextRounds,
            streaming = "",
            busy = true,
            // 关键：用户发送即点亮，不是等 AI 问完
            answeredCount = nextRounds.count { it.answered },
            errorMessage = null,
        )

        streamJob = viewModelScope.launch {
            // 先落库再请求——网络失败也不该让刚说的话消失
            persist(current.noteId, nextRounds)

            val ctx = DeepeningContext(
                noteId = current.noteId,
                originalNote = current.noteBody,
                rounds = flattenForAi(nextRounds),
                roundIndex = nextRounds.count { it.answered } - 1,
                maxRounds = current.maxRounds,
            )

            try {
                ai.streamQuestion(ctx).collect { chunk ->
                    _state.value = _state.value.copy(
                        streaming = _state.value.streaming + chunk,
                    )
                }
            } catch (e: Exception) {
                // 回答已经保留，只是没拿到下一个问题。
                // 用户点「再试一次」就能接着问，不必重说一遍
                _state.value = _state.value.copy(
                    busy = false,
                    errorMessage = humanize(e),
                )
                return@launch
            }

            val newQuestion = _state.value.streaming
            val appended = if (newQuestion.isBlank()) {
                _state.value.rounds
            } else {
                _state.value.rounds + Round(question = newQuestion, answer = null)
            }
            _state.value = _state.value.copy(
                rounds = appended,
                streaming = "",
                busy = false,
                focusTick = if (newQuestion.isBlank()) {
                    _state.value.focusTick
                } else {
                    _state.value.focusTick + 1
                },
            )
            if (newQuestion.isNotBlank()) persist(current.noteId, appended)
        }
    }

    /** 点气泡跳过打字机——直接把剩余内容一次性显示 */
    fun skipTyping() {
        val current = _state.value
        if (current.streaming.isBlank()) return
        val next = current.rounds + Round(question = current.streaming, answer = null)
        streamJob?.cancel()
        streamJob = null
        _state.value = current.copy(
            rounds = next,
            streaming = "",
            busy = false,
            focusTick = current.focusTick + 1,
        )
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

    private suspend fun persist(noteId: String, rounds: List<Round>, finished: Boolean = false) {
        if (rounds.none { it.answered }) return
        repo.saveDeepening(
            Deepening(
                id = "d_$noteId",
                noteId = noteId,
                rounds = rounds,
                startedAt = System.currentTimeMillis(),
                finishedAt = if (finished) System.currentTimeMillis() else null,
                estimatedRounds = _state.value.maxRounds,
            )
        )
    }

    private fun humanize(e: Exception): String =
        ErrorMapper.humanize(
            (e as? TransportException)?.kind ?: ErrorKind.NETWORK_UNAVAILABLE
        )

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
     * 每次「该用户说话了」就 +1。
     *
     * UI 用它触发自动聚焦。用布尔值做不到——从 false 变 true 只触发一次，
     * 第二个问题到来时值没变，键盘就不会再弹。
     */
    val focusTick: Int = 0,
) {
    /** 已完成的问答对数 */
    val completedRounds: Int get() = rounds.count { it.answered }

    /** 有一个问题正在等用户回答——决定输入框能不能用 */
    val awaitingAnswer: Boolean
        get() = rounds.lastOrNull()?.let { !it.answered && it.question.isNotBlank() } == true

    /** 最后一轮之后不再显示新的 AI 气泡（有正在流的也算） */
    val showTypingBubble: Boolean get() = streaming.isNotEmpty()

    /** 折叠后每条显示什么——用户原话，不是 AI 总结 */
    fun summaryOf(round: Round): String =
        round.answer?.takeIf { it.isNotBlank() } ?: "（未作答）"
}
