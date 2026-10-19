package com.hatio.speakinspire.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.ui.deepening.DeepeningUiState
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
@OptIn(ExperimentalMaterial3Api::class)
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
) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val inputFocus = remember { FocusRequester() }

    // 有新内容时滚到底部
    LaunchedEffect(state.rounds.size, state.streaming) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    // 有问题在等回答 → 自动弹键盘。
    //
    // 真机反馈的「点 AI 追问后卡住」有一半是这个：
    // 页面停在那里，输入框要用户先点一下才知道能打字。
    // 有问题等着就该直接能打字——意图不用解释。
    //
    // 用递增的 focusTick 而不是布尔值：布尔值从 false 变 true 只触发一次，
    // 第二个问题到来时值没变，键盘就不会再弹。
    LaunchedEffect(state.focusTick) {
        if (state.focusTick > 0) {
            inputFocus.requestFocus()
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
                        ProgressDots(
                            lit = state.answeredCount,
                            total = state.maxRounds,
                        )
                    }
                },
                actions = {
                    if (state.completedRounds > 0 && !state.finished) {
                        TextButton(onClick = onFinishEarly) {
                            Text("收尾", fontSize = 13.sp)
                        }
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
                .padding(padding),
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
                        OriginCard(state.noteBody)
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

                // 已完成的轮次：折叠成一行
                items(
                    state.rounds.filter { it.answered },
                    key = { it.hashCode() },
                ) { round ->
                    FoldedRound(summary = state.summaryOf(round))
                }

                // 正在流的 AI 气泡
                if (state.showTypingBubble) {
                    item("typing") {
                        AiBubble(text = state.streaming, streaming = true, onSkip = onSkip)
                    }
                }

                // 收尾卡
                if (state.finished || state.completedRounds >= state.maxRounds) {
                    item("finish") {
                        FinishCard(
                            composing = state.composing,
                            userSaidCount = state.completedRounds,
                            onCompose = onCompose,
                            onKeep = onKeep,
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

            // 输入区
            if (!state.finished) {
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
}

/**
 * 进度点。
 *
 * **用户发送后才点亮**——这是刻意的。
 * 如果 AI 问完就点亮，用户会觉得「只要它问完就算我答过了」，
 * 于是敷衍作答。点亮必须代表用户真的说了什么。
 */
@Composable
fun ProgressDots(lit: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (i < lit) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
            )
        }
    }
}

/** 原始笔记。灰色底，表示「这是起点」 */
@Composable
private fun OriginCard(body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(Spacing.m),
    ) {
        Text(
            text = "你随手记的",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 折叠的已完成轮次。
 *
 * 显示**用户原话**——不是 AI 的问题。
 * 用户记得的是自己说过什么，而 AI 总结会把原话再加工一遍，
 * 让他怀疑「我说的是这个意思吗」。
 */
@Composable
private fun FoldedRound(summary: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = false) { }
            .padding(horizontal = Spacing.s, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(Spacing.s))
        Text(
            text = summary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
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
            .imePadding()
            .navigationBarsPadding()
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