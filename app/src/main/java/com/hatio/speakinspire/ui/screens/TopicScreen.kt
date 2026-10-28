package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.domain.GestureJudge
import com.hatio.speakinspire.domain.SwipeAction
import com.hatio.speakinspire.domain.TopicView
import com.hatio.speakinspire.ui.components.CardAction
import com.hatio.speakinspire.ui.components.ConfirmDeleteDialog
import com.hatio.speakinspire.ui.components.EmptyHint
import com.hatio.speakinspire.ui.components.IdeaCard
import com.hatio.speakinspire.ui.components.toChip
import com.hatio.speakinspire.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 「选题」tab —— 待开拍 + 已拍摄 + 搁置。
 *
 * ## 选题现在自己就是一条资源
 *
 * 以前这里显示的其实是「有选题的记录」，滑一下操作的是那条记录，
 * 所以一条记录有多个选题时根本没法区分。现在每张卡片就是一个 Script，
 * 手势直接作用在它的 id 上。
 *
 * ## 手势语义
 *
 * - 右滑 = 标记已拍摄（拍完了）
 * - 左滑 = 这个角度先不拍，搁置
 *
 * 两者都有撤销条，因为滑动有歧义，误触不可避免。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicScreen(
    topics: List<TopicView>,
    onOpen: (String) -> Unit,
    onMarkShot: (String, () -> Unit) -> Unit,
    onPark: (String) -> Unit,
    onUndoShot: (String) -> Unit,
    onUnpark: (String) -> Unit = {},
    onSettings: () -> Unit = {},
    onDelete: (String) -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选题", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        // 用默认 SnackbarHost：1.3.1 里 Snackbar 有两个必填参数同类型的重载，
        // 命名参数消歧不可靠。撤销改用 showSnackbar 的 actionLabel。
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (topics.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyHint(
                    icon = "◎",
                    title = "还没有待拍的选题",
                    hint = "从思考库里挑一篇文章，转成拍摄选题。",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Spacing.l,
                    end = Spacing.l,
                    top = Spacing.s,
                    bottom = 90.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                items(topics, key = { it.script.id }) { topic ->
                    val id = topic.script.id
                    SwipeableTopicCard(
                        topic = topic,
                        onOpen = { onOpen(id) },
                        onDelete = { pendingDelete = id to topic.title },
                        onSwipe = { action ->
                            when (action) {
                                SwipeAction.MARK_SHOT -> onMarkShot(id) {
                                    scope.launch {
                                        val r = snackbar.showSnackbar(
                                            message = "已标记为已拍摄",
                                            actionLabel = "撤销",
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (r == SnackbarResult.ActionPerformed) onUndoShot(id)
                                    }
                                }

                                SwipeAction.PARK -> {
                                    onPark(id)
                                    scope.launch {
                                        val r = snackbar.showSnackbar(
                                            message = "已搁置",
                                            actionLabel = "撤销",
                                            duration = SnackbarDuration.Short,
                                        )
                                        // 撤销搁置要 unpark，不是 undoShot——
                                        // 以前这里写的是 undoShot，等于把一次没拍的选题
                                        // 又清了一次拍摄时间，什么都撤不回来
                                        if (r == SnackbarResult.ActionPerformed) onUnpark(id)
                                    }
                                }

                                SwipeAction.NONE -> Unit
                            }
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { (id, title) ->
        ConfirmDeleteDialog(
            title = title,
            message = "移入回收站，30 天内可以恢复。\n它对应的文章还在。",
            onConfirm = {
                onDelete(id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 可左右滑的选题卡片。
 *
 * 滑动的判定复用 [GestureJudge]——纯函数，阈值与角度都有测试覆盖，
 * 不在 UI 层另写一套。
 */
@Composable
private fun SwipeableTopicCard(
    topic: TopicView,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onSwipe: (SwipeAction) -> Unit,
) {
    var dragX by remember { mutableStateOf(0f) }

    val hintColor = when {
        dragX > 60 -> MaterialTheme.colorScheme.tertiary      // 已拍摄
        dragX < -60 -> MaterialTheme.colorScheme.error        // 搁置
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(topic.script.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val action = GestureJudge.judge(dragX, 0f)
                        dragX = 0f
                        if (action != SwipeAction.NONE) onSwipe(action)
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragX += amount
                    },
                )
            },
    ) {
        // 滑动时露出底下的提示
        Box(
            modifier = Modifier.matchParentSize(),
            contentAlignment = if (dragX >= 0) Alignment.CenterStart
            else Alignment.CenterEnd,
        ) {
            Text(
                text = if (dragX >= 0) "✓ 已拍摄" else "↩ 搁置",
                fontSize = 13.sp,
                color = hintColor,
                modifier = Modifier.padding(horizontal = Spacing.l),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                // ⚠️ 这里**不能**用 padding(start = dragX.dp)。
                //
                // 左滑时 dragX 是负数，而 Modifier.padding 要求非负，
                // 抛 IllegalArgumentException: Padding must be non-negative。
                // 表现是「左滑一次，App 直接闪退」——搁置这条路从来没走通过。
                //
                // offset 的 lambda 版本吃 IntOffset，负值合法，而且只影响绘制阶段。
                .offset { IntOffset(dragX.coerceIn(-120f, 120f).roundToInt(), 0) },
        ) {
            IdeaCard(
                title = topic.title,
                preview = topic.script.sections.firstOrNull()
                    ?.phrases?.firstOrNull()
                    ?: topic.script.angle,
                chip = topic.state.toChip(),
                timeLabel = topic.script.createdAt.toDateLabel(),
                tagLine = topic.script.angle.takeIf { it.isNotBlank() && it != topic.title },
                actions = listOf(CardAction("删除", onDelete)),
                onClick = onOpen,
            )
        }
    }
}
