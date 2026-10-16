package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.domain.IdeaState
import com.hatio.speakinspire.domain.IdeaStateDeriver
import com.hatio.speakinspire.domain.IdeaView
import com.hatio.speakinspire.ui.components.EmptyHint
import com.hatio.speakinspire.ui.components.IdeaCard
import com.hatio.speakinspire.ui.theme.Spacing

/**
 * 「记录」tab —— 随手记 + 待深化。
 *
 * 这是最高频的页面，所以：
 * - 顶部只有标题和设置按钮，不塞别的东西
 * - 列表默认无 chip（待深化不需要标），扫一眼就知道哪些没聊过
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    ideas: List<IdeaView>,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
) {
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
        if (ideas.isEmpty()) {
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
                items(ideas, key = { it.note.id }) { idea ->
                    IdeaCard(
                        title = idea.note.title.ifBlank { "（无标题）" },
                        preview = idea.note.body,
                        state = IdeaStateDeriver.derive(idea),
                        timeLabel = idea.note.createdAt.toDateLabel(),
                        onClick = { onOpen(idea.note.id) },
                    )
                }
            }
        }
    }
}

/**
 * 「思考」tab —— 已成文章的作品。
 *
 * 文章是资产不是消耗品，所以这里按成稿时间倒序，
 * 「先留着」的自动沉底。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkScreen(
    ideas: List<IdeaView>,
    onOpen: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("思考", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (ideas.isEmpty()) {
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
                items(ideas, key = { it.note.id }) { idea ->
                    IdeaCard(
                        title = idea.article?.title ?: idea.note.title,
                        preview = idea.article?.paragraphs?.firstOrNull()?.text
                            ?: idea.note.body,
                        state = IdeaStateDeriver.derive(idea),
                        timeLabel = idea.article?.writtenAt?.toDateLabel() ?: "",
                        tagLine = if (idea.scripts.isNotEmpty()) {
                            "已转 ${idea.scripts.size} 个选题"
                        } else null,
                        onClick = { onOpen(idea.note.id) },
                    )
                }
            }
        }
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