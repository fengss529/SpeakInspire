package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import com.hatio.speakinspire.domain.TopicState
import com.hatio.speakinspire.domain.TopicView
import com.hatio.speakinspire.ui.components.CardAction
import com.hatio.speakinspire.ui.components.ConfirmDeleteDialog
import com.hatio.speakinspire.ui.components.EmptyHint
import com.hatio.speakinspire.ui.components.IdeaCard
import com.hatio.speakinspire.ui.components.toChip
import com.hatio.speakinspire.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * 「选题」tab —— 待开拍 + 已拍摄 + 搁置。
 *
 * ## 左右滑被删掉了（V0.11）
 *
 * 那两个手势的问题是**它在不该做决定的地方让人做决定**：
 * 卡片上只有标题和一句短语，用户还没看清这条要讲什么，
 * 一个横向滑动就把它的状态改了。滑动的歧义（是滚动还是操作）
 * 加上误触，撤销条成了必需品——而撤销条是在补救一个
 * 本来不该发生的动作。
 *
 * 现在低频动作（搁置 / 删除）放进 ⋮ 菜单，主动作（标记已拍摄）
 * 放到详情页。想改状态，先点开看清楚。
 *
 * ## 一张卡片就是一个选题
 *
 * 以前这里显示的其实是「有选题的记录」，滑一下操作的是那条记录，
 * 所以一条记录有多个选题时根本没法区分。现在每张卡片就是一个 Script。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicScreen(
    topics: List<TopicView>,
    onOpen: (String) -> Unit,
    onPark: (String) -> Unit,
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
                    IdeaCard(
                        title = topic.title,
                        preview = topic.script.sections.firstOrNull()
                            ?.phrases?.firstOrNull()
                            ?: topic.script.angle,
                        chip = topic.state.toChip(),
                        timeLabel = topic.script.createdAt.toDateLabel(),
                        tagLine = topic.script.angle.takeIf {
                            it.isNotBlank() && it != topic.title
                        },
                        actions = buildList {
                            // 已拍摄的选题不给「搁置」：状态派生里「已拍摄」
                            // 压过「搁置」，点了会什么也看不见——一个点了
                            // 没反应的菜单项，比没有这一项更糟
                            when (topic.state) {
                                TopicState.PARKED -> add(
                                    CardAction("取消搁置") { onUnpark(id) },
                                )
                                TopicState.TODO -> add(
                                    CardAction("搁置") {
                                        onPark(id)
                                        scope.launch {
                                            val r = snackbar.showSnackbar(
                                                message = "已搁置",
                                                actionLabel = "撤销",
                                                duration = SnackbarDuration.Short,
                                            )
                                            if (r == SnackbarResult.ActionPerformed) onUnpark(id)
                                        }
                                    },
                                )
                                TopicState.SHOT -> Unit
                            }
                            add(CardAction("删除") { pendingDelete = id to topic.title })
                        },
                        onClick = { onOpen(id) },
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
