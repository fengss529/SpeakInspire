package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.domain.IdeaStateDeriver
import com.hatio.speakinspire.domain.IdeaView
import com.hatio.speakinspire.domain.GestureJudge
import com.hatio.speakinspire.domain.SwipeAction
import com.hatio.speakinspire.ui.components.EmptyHint
import com.hatio.speakinspire.ui.components.IdeaCard
import com.hatio.speakinspire.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 「选题」tab —— 待开拍 + 已拍摄。
 *
 * ## 手势语义（决策 Q5）
 *
 * - 右滑 = 标记已拍摄（拍完了）
 * - 左滑 = 这个角度先不拍，移回思考库
 *
 * 左滑**不是删除**——文章本身没错，选题不合适不等于文章有问题。
 * 两者都有撤销条，因为滑动有歧义，误触不可避免。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicScreen(
    ideas: List<IdeaView>,
    onOpen: (String) -> Unit,
    onMarkShot: (String, () -> Unit) -> Unit,
    onPark: (String) -> Unit,
    onUndoShot: (String) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选题", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        // 用默认 SnackbarHost：1.3.1 里 Snackbar 有两个必填参数同类型的重载，
        // 命名参数消歧不可靠。撤销改用 showSnackbar 的 actionLabel。
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (ideas.isEmpty()) {
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
                items(ideas, key = { it.note.id }) { idea ->
                    SwipeableTopicCard(
                        idea = idea,
                        onOpen = { onOpen(idea.note.id) },
                        onSwipe = { action ->
                            when (action) {
                                SwipeAction.MARK_SHOT -> {
                                    var noteId: String? = null
                                    onMarkShot(idea.note.id) {
                                        noteId = idea.note.id
                                        scope.launch {
                                            val result = snackbar.showSnackbar(
                                                message = "已标记为已拍摄",
                                                actionLabel = "撤销",
                                                duration = SnackbarDuration.Short,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                onUndoShot(idea.note.id)
                                            }
                                        }
                                    }
                                }
                                SwipeAction.PARK -> {
                                    onPark(idea.note.id)
                                    scope.launch {
                                        val result = snackbar.showSnackbar(
                                            "已移回思考库",
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            onUndoShot(idea.note.id)
                                        }
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
}

/**
 * 可左右滑的选题卡片。
 *
 * 滑动的判定复用 [GestureJudge]——纯函数，阈值与角度都有测试覆盖，
 * 不在 UI 层另写一套。
 */
@Composable
private fun SwipeableTopicCard(
    idea: IdeaView,
    onOpen: () -> Unit,
    onSwipe: (SwipeAction) -> Unit,
) {
    val density = LocalDensity.current
    var dragX by remember { mutableStateOf(0f) }

    val hintColor = when {
        dragX > 60 -> MaterialTheme.colorScheme.tertiary      // 已拍摄
        dragX < -60 -> MaterialTheme.colorScheme.error        // 移回
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(idea.note.id) {
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
                text = if (dragX >= 0) "✓ 已拍摄" else "↩ 移回",
                fontSize = 13.sp,
                color = hintColor,
                modifier = Modifier.padding(horizontal = Spacing.l),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = with(density) { (dragX.coerceIn(-120f, 120f)).dp }),
        ) {
            IdeaCard(
                title = idea.article?.title ?: idea.note.title,
                preview = idea.scripts.firstOrNull { !it.parked }
                    ?.sections?.firstOrNull()?.phrases?.firstOrNull()
                    ?: idea.article?.paragraphs?.firstOrNull()?.text
                    ?: idea.note.body,
                state = IdeaStateDeriver.derive(idea),
                timeLabel = idea.article?.writtenAt?.toDateLabel() ?: "",
                tagLine = idea.scripts.firstOrNull { !it.parked }?.angle,
                onClick = onOpen,
            )
        }
    }
}