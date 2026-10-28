package com.hatio.speakinspire.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.ui.deepening.DeepeningUiState
import com.hatio.speakinspire.ui.text.PickableText
import com.hatio.speakinspire.ui.text.SelectableText
import com.hatio.speakinspire.ui.theme.Spacing

/**
 * 追问对话页 —— 整个 App 的核心。
 *
 * ## 布局原则
 *
 * **AI 在左（浅底），我在右（深底）**。对话的方向感靠这个建立。
 *
 * ## 折叠规则
 *
 * 历史轮次折叠成一行，显示**用户原话**而不是 AI 的问题——
 * 因为用户记得的是自己说过什么。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DeepeningScreen(
    state: DeepeningUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onSkip: () -> Unit,
    onFinishEarly: () -> Unit,
    onCompose: () -> Unit,
    /** 「先留着」——收尾但不写文章，回到记录详情 */
    onKeep: () -> Unit,
    /** 没有任何问题时，让用户能重新触发一次——卡住时的兜底出路 */
    onRetry: () -> Unit,
    /**
     * 重新打开已收尾的会话。
     *
     * 成文页说「回去再聊聊」，回去必须真的能聊。
     */
    onReopen: () -> Unit = {},
    /** 改一句已经说过的话。参数是它在 rounds 里的下标 */
    onUpdateAnswer: (Int, String) -> Unit = { _, _ -> },
    /** 编辑最初那条随手记（走详情页的编辑流程） */
    onEditOrigin: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val inputFocus = remember { FocusRequester() }

    // 正在改的那一句（rounds 下标）。null = 没在改
    var editingRound by remember { mutableStateOf<Int?>(null) }

    /**
     * 历史列表贴底。
     *
     * ## 为什么这里只剩下「轮数变了就贴底」
     *
     * 原来还有两个触发条件：`streaming` 和 `imeVisible`。
     * 它们是为了让**最新那一问**不被键盘盖住——但那个做法注定不稳：
     * 键盘弹出的动画有 200-300ms，期间 LazyColumn 的视口每一帧都在变矮，
     * 而 `animateScrollToItem` 只认它开始那一刻的布局。
     * 滚一次，算错一次，最后仍然是差一截。
     *
     * 现在的解法不是滚得更聪明，而是**那一问根本不在列表里**：
     * 见下方的 CurrentTurn，它和输入框一起被 imePadding 抬起，
     * 只要输入框看得见，问题就看得见。
     *
     * 所以这里只剩一件事：多了一轮历史，就把列表带到最底下。
     */
    LaunchedEffect(state.rounds.size) {
        withFrameNanos { }
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) runCatching { listState.animateScrollToItem(total - 1) }
    }

    /**
     * 输入框是否会出现在这一帧里。
     *
     * **这个条件必须只有一处。** 它同时决定两件事：
     * 底部要不要画 InputBar、自动聚焦能不能请求。
     *
     * 曾经两处各写各的（InputBar 用 `!finished`，聚焦不看条件），
     * 结果从成文页点「回去再聊聊」时崩溃：
     * ViewModel 被复用，`focusTick` 还留着上次的值（> 0），
     * LaunchedEffect 重新启动就立刻 requestFocus——
     * 可那时输入框根本没渲染，`Modifier.focusRequester()` 没有挂到任何节点上，
     * Compose 抛 `IllegalStateException: FocusRequester is not initialized`。
     */
    val canInput = !state.finished || state.pendingQuestion != null

    // 有问题在等回答 → 自动弹键盘。
    //
    // 真机反馈的「点 AI 追问后卡住」有一半是这个：
    // 页面停在那里，输入框要用户先点一下才知道能打字。
    // 有问题等着就该直接能打字——意图不用解释。
    //
    // 用递增的 focusTick 而不是布尔值：布尔值从 false 变 true 只触发一次，
    // 第二个问题到来时值没变，键盘就不会再弹。
    LaunchedEffect(state.focusTick) {
        if (state.focusTick > 0 && canInput) {
            // 即便条件成立，也可能落在「节点还没挂上」的边界帧。
            // 这不是需要处理的错误，只是一个还没准备好的请求——
            // 让用户自己点一下，比让整个页面崩掉好。
            runCatching { inputFocus.requestFocus() }
        }
    }

    val canSend = input.isNotBlank() && !state.busy

    // 空状态：既没有历史轮次，也没有任何问题。
    // 这时候页面是死的——必须给一条出路。
    val isBlank = state.rounds.isEmpty() &&
        state.streaming.isBlank() &&
        state.errorMessage == null &&
        !state.busy

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.noteTitle.ifBlank { "深化" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        // 只标一句问法，不放进度点。
                        //
                        // 五个小灰点看着像进度，实际什么也不说明：
                        // 用户不知道自己该聊几轮，而"还剩几个"恰恰是
                        // 这版刻意不显示的——倒计时会诱发敷衍作答。
                        // 一个永远数不出意义的点阵，只是装饰。
                        //
                        // 留下方法名是有用的：用户在设置里换了问法，
                        // 回到这里如果毫无变化，他会怀疑「是不是没生效」。
                        Text(
                            text = state.probeMethod.shortLabel,
                            fontSize = 11.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                },
                actions = {
                    // 「收尾」这个出口必须活着。
                    //
                    // 平时 `finished` 为 true 就不必再收了。但有一种状态
                    // 会让两条路同时封死：会话被标记过收尾（旧数据，
                    // 或上一版 finishEarly 没丢掉最后那句），而末尾还挂着
                    // 一个没回答的问题。这时收尾卡被 `!hasPending` 挡住，
                    // 按钮又因为 `!finished` 不显示——用户看得见问题、
                    // 打得了字，却没有任何办法结束它。
                    //
                    // 所以：只要还有问题待答，这个按钮就得留着。
                    // 点下去 finishEarly() 会把它一起收掉，收尾卡才出得来。
                    val canStillFinish = !state.finished || state.pendingQuestion != null
                    if (state.completedRounds > 0 && canStillFinish) {
                        TextButton(onClick = onFinishEarly) {
                            Text("收尾", fontSize = 13.sp)
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // 把 padding 已经用掉的那部分 insets 声明为「已消费」，
                // 否则下面的 imePadding 会把导航栏高度再算一遍，
                // 键盘上方多出一条空白
                .consumeWindowInsets(padding)
                // 整个内容区（列表 + 输入栏）一起抬到键盘之上。
                //
                // 之前只给输入栏加 imePadding：输入栏浮起来了，
                // 列表还是原来的高度，两者互相重叠，最新的问题被压在键盘后面。
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = Spacing.l,
                    vertical = Spacing.m,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                // 原始笔记置顶，只展示一次
                if (state.noteBody.isNotBlank()) {
                    item("origin") {
                        OriginCard(
                            body = state.noteBody,
                            onEdit = onEditOrigin,
                            // 「捡起一句」的去处：收进当前回答。
                            // 追问阶段用户常常「想说的说不出」，
                            // 而他自己的原话就在上面摆着——
                            // 让他把其中一句拣回来当起点，比让他从空白框开始容易得多
                            onPickSentence = { picked ->
                                input = if (input.isBlank()) picked else "$input $picked"
                            },
                        )
                    }
                }

                // 什么都还没有：给一条出路。
                // 真机反馈的「点 AI 追问后卡住」最难受的地方就是这里——
                // 页面空白、没有错误、没有按钮，用户只能退出，
                // 而且不知道是网络问题还是 App 坏了。
                if (isBlank) {
                    item("blank") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xl),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "还没开始",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                text = "网络慢的话可以再试一次",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.height(Spacing.l))
                            androidx.compose.material3.OutlinedButton(
                                onClick = onRetry,
                            ) {
                                Text("重新问一次")
                            }
                        }
                    }
                }

                // 已完成的轮次：问答成对显示。
                //
                // 曾经只显示用户原话（一行灰点）。那条规则的**本意**是
                // 「不要让 AI 替你总结你说过的话」——结果执行成了
                // 「连 AI 问过什么都不显示」，于是回看时根本不知道自己
                // 那一句是在回答什么，六个孤零零的句子排在那里。
                //
                // 现在问题和回答都在，方向靠左右区分（左问右答），
                // 视觉权重比当前这一轮轻，历史感保留。
                //
                // ⚠️ key 用**下标**，不能用 `it.hashCode()`。
                //
                // Round 是 data class，hashCode 由 question + answer 算出来。
                // 两轮内容完全一样时（同一句话说过两遍、或者模型问了同一个问题，
                // 这在真实对话里很常见），hashCode 就相同——LazyColumn 遇到重复 key
                // 会直接抛 IllegalArgumentException，整个页面崩掉。
                //
                // 真机反馈的「点回去再聊时 App 退出」就发生在这条路径上：
                // 从成文页跳回追问页，重新组合列表，key 撞了就崩。
                // 带上它在 rounds 里的**原始下标**。
                //
                // 改答案时要改对那一轮；用「第几个已回答」当坐标，
                // 一旦中间夹着未答轮次（收尾前的残渣、流式中断）
                // 就会改到错误的那句话上。
                val answered = state.rounds.withIndex().filter { it.value.answered }
                itemsIndexed(
                    answered,
                    key = { _, entry -> "turn_${entry.index}" },
                ) { _, entry ->
                    HistoryTurn(
                        question = entry.value.question,
                        answer = entry.value.answer.orEmpty(),
                        onEdit = { editingRound = entry.index },
                    )
                }

                // 收尾卡。
                //
                // 判定收在 DeepeningUiState.showFinishCard 里，不在这儿拼条件——
                // 那里排除了 busy / streaming，卡片才不会「闪一下又消失」。
                // 有问题在等回答时同样不出现：AI 刚问完一句，界面却宣布
                // 「你说完了」，然后下面摆着输入框——那是在跟用户说两句话。
                if (state.showFinishCard) {
                    item("finish") {
                        FinishCard(
                            composing = state.composing,
                            userSaidCount = state.completedRounds,
                            onCompose = onCompose,
                            onKeep = onKeep,
                            onReopen = onReopen,
                        )
                    }
                }

                // 错误提示
                state.errorMessage?.let { msg ->
                    item("error") {
                        ErrorHint(msg, onRetry = onRetry)
                    }
                }
            }

            // 当前轮次：**钉在输入框正上方**，不参与列表滚动。
            //
            // ## 键盘遮挡为什么在结构上被解决了
            //
            // 之前这一问是 LazyColumn 的最后一项。键盘一弹，
            // 视口从底部被削掉一截，而列表锚定的是旧的可见项——
            // 最后那一项正好落在被削掉的区域里。用户看到的就是
            // 「问题被键盘盖住了，要手动往上拨一下」。
            //
            // 试过用滚动去追（监听 ime 变化、等一帧、animateScrollToItem），
            // 真机上仍然差一截：键盘动画 200-300ms，滚动只认开始那一刻的布局。
            //
            // 现在它不在列表里了。这里的 Column 带 imePadding，
            // 键盘抬起时整个内容区一起上移，输入框和它上面这一问
            // 是同一个整体——**只要输入框看得见，问题就一定看得见**。
            // 跟滚动、跟时机、跟帧数都没有关系了。
            CurrentTurn(
                streaming = state.streaming,
                pending = state.pendingQuestion,
                onSkip = onSkip,
            )

            // 输入区
            //
            // **只要有一个问题在等回答，就必须能输入**，哪怕会话已经收尾。
            //
            // 真机截图里的矛盾状态就是这么来的：AI 刚问完一个问题，
            // 用户恰好点了「收尾」（那时 `skipTyping()` 把内容落地成待答轮次，
            // 同时 finished = true），于是界面上问题清清楚楚摆着，
            // 输入框却被 `!finished` 藏掉了——看着一个问题，打不了字。
            //
            // 条件和上方的自动聚焦共用 canInput，两处不能各写各的：
            // 聚焦请求需要这里真的挂上 Modifier.focusRequester。
            if (canInput) {
                InputBar(
                    focusRequester = inputFocus,
                    value = input,
                    // 只有「确实有一个问题在等回答」时才能输入。
                    //
                    // 之前用 answeredCount > 0 判断：重新进入一条只问过、
                    // 还没答过的记录时 answeredCount 是 0，输入框永久禁用，
                    // 用户看着一个问题却打不了字——和「卡住」是同一类死局。
                    enabled = !state.busy && state.awaitingAnswer,
                    placeholder = when {
                        state.busy && state.rounds.isEmpty() -> "AI 正在想第一个问题…"
                        state.busy -> "AI 正在想下一个问题…"
                        state.awaitingAnswer -> "回应它"
                        // 出错后输入框是灰的。写「等一会儿」会让人以为还在转，
                        // 于是就一直等——这里必须指向那唯一的出路
                        state.errorMessage != null -> "先点上面的「再试一次」"
                        else -> "等一会儿…"
                    },
                    onChange = { input = it },
                    onSend = {
                        onSend(input)
                        input = ""
                    },
                    canSend = canSend,
                )
            }
        }
    }

    // 改一句话的弹窗。
    //
    // 放在 Scaffold 之外：它是覆盖层，不该参与页面布局，
    // 也不该因为列表滚动而重新测量。
    editingRound?.let { idx ->
        val original = state.rounds.getOrNull(idx)?.answer.orEmpty()
        AnswerEditDialog(
            initial = original,
            onDismiss = { editingRound = null },
            onConfirm = { text ->
                onUpdateAnswer(idx, text)
                editingRound = null
            },
        )
    }
}

/**
 * 改一句自己说过的话。
 *
 * 只有一个输入框和两个按钮——不做富文本、不做历史版本。
 * 用户要的是「把这句话说顺一点」，不是编辑器。
 */
@Composable
private fun AnswerEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("改这句话", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        },
        text = {
            Column {
                Text(
                    text = "改完之后，成文用的是新的说法。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(Spacing.s))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank() && text.trim() != initial.trim(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 原始笔记。灰色底，表示「这是起点」
 *
 * 右上角留一个「编辑」：追问聊到一半发现最初那句记错了，
 * 不该逼用户退出去找详情页——他这时候正好知道该改哪里。
 */
@Composable
private fun OriginCard(
    body: String,
    onEdit: () -> Unit = {},
    /** 选中一句之后交出去。为 null 时只给原生复制 */
    onPickSentence: ((String) -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(Spacing.m),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "你随手记的",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "编辑",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        // 这段文字有两个用途：看清自己写了什么，以及把其中一句捡回来。
        // 后者需要拿到选中的内容，所以走只读输入框那套
        val style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onPickSentence != null) {
            PickableText(
                text = body,
                style = style,
                onPick = onPickSentence,
                pickLabel = "收进回答",
            )
        } else {
            SelectableText(text = body, style = style)
        }
    }
}

/**
 * 一轮已完成的问答。
 *
 * ## 左问右答
 *
 * 方向感是这个界面最要紧的东西：**问题在左，我的回答在右**，
 * 和当前这一轮（[AiBubble] 在左）保持同一套语言，用户不用重新学。
 *
 * ## 为什么不再只显示用户原话
 *
 * 早期这里是一条灰点加一句用户原话，理由是「不要让 AI 总结用户的话」。
 * 这个理由本身没错，但执行成了「连 AI 问过什么都不显示」——
 * 回看历史时，六个孤零零的句子排在那里，谁也不知道
 * 「Sense of meaning in life」是在回答哪个问题。
 *
 * 显示 AI 的**原问题**和「让 AI 总结用户的话」是两件完全不同的事。
 *
 * ## 视觉上要比当前轮次轻
 *
 * 历史是已经过去的，字号小一号、底色淡一档、行距紧一点。
 * 但可读性不能降——它存在的意义就是让人回看。
 */
@Composable
private fun HistoryTurn(
    question: String,
    answer: String,
    /** 点「改」时回调。答案在历史里不是只读的——见 AnswerEditDialog */
    onEdit: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.m),
    ) {
        // 问题：左
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 2.dp,
                            topEnd = 12.dp,
                            bottomEnd = 12.dp,
                            bottomStart = 12.dp,
                        )
                    )
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                // 问题也能选中复制——用户可能想把这个问题记下来问别人。
                // 但不给「收进回答」：把 AI 的问题抄进自己的回答里，
                // 等于把自己要说的话换成别人（哪怕是 AI）的措辞
                SelectableText(
                    text = question,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }

        Spacer(Modifier.height(5.dp))

        // 我的回答：右。颜色比问题重一点——这是他自己说的话
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 12.dp,
                            topEnd = 2.dp,
                            bottomEnd = 12.dp,
                            bottomStart = 12.dp,
                        )
                    )
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Column {
                    SelectableText(
                        text = answer,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        // 有人一句话能说几百字。历史里留 8 行足够辨认，
                        // 再多就把页面顶到很远——要回看原文可以去成文页
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 「改」挂在气泡内右下角。
                    //
                    // 不放图标：这个 App 的用词都很直白，
                    // 一个铅笔图标反而要多想一秒才敢点。
                    if (onEdit != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(
                                text = "改",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onEdit)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 当前这一问 —— 钉在输入框正上方，不参与列表滚动。
 *
 * 这是整个 App 里唯一「必须始终可见」的文字：用户此刻要回答的，
 * 就是这一句。它被键盘盖住一次，用户就得先拨一下才能作答，
 * 而每次追问都要拨一下的话，这个功能就没法用了。
 *
 * 高度给了上限并允许内部滚动：模型偶尔会长篇输出，
 * 不限制的话它会把上面的历史压成一条缝，键盘弹起时甚至把输入框顶出屏幕。
 */
@Composable
private fun CurrentTurn(
    streaming: String,
    pending: String?,
    onSkip: () -> Unit,
) {
    val text = streaming.ifEmpty { pending.orEmpty() }
    if (text.isBlank()) return
    val isStreaming = streaming.isNotEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = Spacing.l),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l)
                .padding(top = Spacing.s, bottom = Spacing.xs),
        ) {
            Text(
                text = if (isStreaming) "正在问…" else "现在回这句",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                AiBubble(text = text, streaming = isStreaming, onSkip = onSkip)
            }
        }
    }
}

/**
 * AI 气泡。
 *
 * 正在流式时显示闪烁光标，点一下跳过。
 */
@Composable
private fun AiBubble(
    text: String,
    streaming: Boolean,
    onSkip: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 14.dp,
                        bottomEnd = 14.dp,
                        bottomStart = 14.dp,
                    )
                )
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .clickable(enabled = streaming) { onSkip() }
                .padding(Spacing.m),
        ) {
            Text(
                text = text,
                fontSize = 15.sp,
                lineHeight = 23.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (streaming) {
                Text(
                    text = "▍",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                )
            }
        }
    }
}

/**
 * 收尾卡。
 *
 * **只有两个动作，没有任何 AI 总结。**
 * 前面五轮 AI 一直在克制不给答案，若在这里突然来一段总结，
 * 等于撤销了整段对话建立起来的信任。
 *
 * 所以只放两件事：你说过的话 + 两个按钮。
 */
@Composable
private fun FinishCard(
    composing: Boolean,
    userSaidCount: Int,
    onCompose: () -> Unit,
    onKeep: () -> Unit,
    onReopen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.m)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(Spacing.l),
    ) {
        Text(
            text = "你说完了",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "说了 $userSaidCount 轮。接下来是把这些整理成文章，" +
                "还是会回思考库放着——都由你定。",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.l))

        Button(
            onClick = onCompose,
            enabled = !composing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (composing) "整理中…" else "写成文章")
        }
        Spacer(Modifier.height(Spacing.s))

        // 「先留着」是平级选项，不是取消——
        // 深度追问后不想要文章是完全合理的。
        //
        // 它必须真的做事：收尾并回到详情页。
        // 之前 onClick 是空 lambda，点了没反应，用户会以为按钮坏了。
        OutlinedButton(
            onClick = onKeep,
            enabled = !composing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("先留着")
        }

        // 收尾不是锁。
        //
        // 成文页素材不够时会给一句「回去再聊聊」，用户回来却只看到
        // 这张卡——没有输入框，聊不了，那句话等于死路。
        // 这道门必须留在这儿。
        Spacer(Modifier.height(Spacing.xs))
        TextButton(
            onClick = onReopen,
            enabled = !composing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("还没说完，再聊几句", fontSize = 13.sp)
        }
    }
}

@Composable
private fun ErrorHint(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .padding(Spacing.m),
    ) {
        Text(
            text = message,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        // 报错必须配一条出路。
        // 只有错误文案的话，用户唯一能做的还是退出。
        TextButton(onClick = onRetry) {
            Text("再试一次", fontSize = 13.sp)
        }
    }
}

/** 底部输入栏 */
@Composable
private fun InputBar(
    focusRequester: FocusRequester,
    value: String,
    enabled: Boolean,
    placeholder: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // imePadding / navigationBarsPadding 已经移到外层 Column——
            // 只抬输入栏会把列表留在键盘后面
            .padding(Spacing.m),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.primary
                ),
                // 有问题等回答时靠它自动弹键盘
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
        Spacer(Modifier.width(Spacing.s))
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable(enabled = canSend) { onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "↑",
                fontSize = 20.sp,
                color = if (canSend) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.outline,
            )
        }
    }
}