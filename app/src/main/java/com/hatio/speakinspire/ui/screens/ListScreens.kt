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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hatio.speakinspire.data.export.displayTitle
import com.hatio.speakinspire.domain.ArticleView
import com.hatio.speakinspire.domain.NoteView
import com.hatio.speakinspire.ui.components.CardAction
import com.hatio.speakinspire.ui.components.EmptyHint
import com.hatio.speakinspire.ui.components.IdeaCard
import com.hatio.speakinspire.ui.components.toChip
import com.hatio.speakinspire.ui.components.ConfirmDeleteDialog
import com.hatio.speakinspire.ui.theme.Spacing

/**
 * 「记录」tab —— 我记下过的东西。
 *
 * 这是最高频的页面，所以：
 * - 顶部只有标题和设置按钮，不塞别的东西
 * - 列表默认无 chip（「记录」是默认状态），扫一眼就知道哪些没聊过
 *
 * 它是「我记过什么」的清单，**不是流水线的一环**：
 * 深化完、成完文，这条仍然留在这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    notes: List<NoteView>,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onDelete: (String) -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记录", style = MaterialTheme.typography.titleLarge) },
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
    ) { padding ->
        if (notes.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyHint(
                    icon = "✎",
                    title = "还没有想法",
                    hint = "点下方的加号，随手记一条。\n想到什么就写什么，不用整理。",
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
                items(notes, key = { it.note.id }) { view ->
                    IdeaCard(
                        title = view.note.displayTitle,
                        preview = view.note.body,
                        chip = view.state.toChip(),
                        timeLabel = view.note.createdAt.toDateLabel(),
                        actions = listOf(
                            CardAction("删除") { pendingDelete = view.note.id to view.note.displayTitle }
                        ),
                        onClick = { onOpen(view.note.id) },
                    )
                }
            }
        }
    }

    pendingDelete?.let { (id, title) ->
        ConfirmDeleteDialog(
            title = title,
            message = "移入回收站，30 天内可以恢复。\n追问和发芽会跟着一起走——它们是这条记录自己的东西。",
            onConfirm = {
                onDelete(id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 「思考」tab —— 文章独立成条。
 *
 * 这里以前显示的是「有文章的记录」，所以删一篇文章其实是删一条记录。
 * 现在每一条就是文章本身，按成稿时间倒序，没有状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkScreen(
    articles: List<ArticleView>,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit = {},
    onDelete: (String) -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("思考", style = MaterialTheme.typography.titleLarge) },
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
    ) { padding ->
        if (articles.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyHint(
                    icon = "❐",
                    title = "思考库还是空的",
                    hint = "把想法深化几轮，AI 会帮你梳理成一篇完整的文章。\n文章会永久留在这里。",
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
                items(articles, key = { it.article.id }) { view ->
                    IdeaCard(
                        title = view.article.title.ifBlank { "（无标题）" },
                        preview = view.preview,
                        timeLabel = view.article.writtenAt.toDateLabel(),
                        // 两个标签是**同时成立**的两件事，不能用一个 when 互相盖掉：
                        // 一篇文章可以既转出了选题、出处记录又被删了。
                        // 之前写成互斥的 when，「出处已删除」在转过选题之后就永远看不到了。
                        tagLine = buildList {
                            if (view.topicCount > 0) add("已转 ${view.topicCount} 个选题")
                            // 要有过出处才谈得上「已删除」——本来就没有出处的文章
                            // 说「原始记录已删除」是假话
                            if (view.hadSource && !view.hasSource) add("原始记录已删除")
                        }.joinToString(" · ").ifBlank { null },
                        actions = listOf(
                            CardAction("删除") {
                                pendingDelete = view.article.id to view.article.title
                            }
                        ),
                        onClick = { onOpen(view.article.id) },
                    )
                }
            }
        }
    }

    pendingDelete?.let { (id, title) ->
        ConfirmDeleteDialog(
            title = title,
            message = "移入回收站，30 天内可以恢复。\n由它转出的选题不会跟着消失。",
            onConfirm = {
                onDelete(id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** 时间戳 → 「10 月 8 日」这样的标签 */
fun Long.toDateLabel(): String {
    if (this <= 0) return ""
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = this
    val month = cal.get(java.util.Calendar.MONTH) + 1
    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
    return "$month 月 $day 日"
}
