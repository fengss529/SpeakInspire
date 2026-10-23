package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.ui.theme.Spacing

/**
 * 记录条详情页 —— 这条记录的控制台。
 *
 * ## 为什么要有这一页
 *
 * 之前点开记录条直接进追问页，导致：
 * - 用户看不到自己到底记了什么
 * - 想改个字都做不到，只能重新记一条
 * - 不知道该做什么操作——是输入还是等 AI 问？
 *
 * ## 为什么是四个并列的入口，而不是「下一步」
 *
 * 这一页曾经只有一个主按钮「AI 追问」，暗示着一条必须走完的路：
 * 追问 → 成文 → 转选题 → 拍摄。任何一环卡住，这条记录就废在那里。
 *
 * 但记录本来的样子不是这样。一条想法可以：
 * - 只是想记着（那就退出去）
 * - 想让人追问几轮（追问）
 * - 想让 AI 往外连一点别的东西（发芽）
 * - 已经有文章了，回来看一眼（看文章）
 *
 * 所以这里是**控制台**：四个入口并列，谁也不比谁优先。
 * 用户不需要知道「流程走到哪一步」，只需要知道「我现在想干什么」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    title: String,
    body: String,
    createdLabel: String,
    rounds: List<Round>,
    hasArticle: Boolean,
    /** 是否已有可续的对话——决定按钮文案 */
    hasDeepened: Boolean,
    /** 发芽按钮文案：长过芽就带次数 */
    sproutLabel: String = "发芽",
    /** 最近一次发芽的标题，没有就空 */
    latestSproutTitle: String = "",
    exportMessage: String? = null,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeepen: () -> Unit,
    onSprout: () -> Unit = {},
    onViewArticle: () -> Unit,
    onSettings: () -> Unit = {},
    onExport: () -> Unit = {},
    onDismissMessage: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }

    // 导出失败要说一声。点了没反应和「导出成功但找不到文件」
    // 在用户那里是一回事：按钮坏了
    LaunchedEffect(exportMessage) {
        exportMessage?.let {
            snackbar.showSnackbar(it)
            onDismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text("记录", style = MaterialTheme.typography.titleMedium)
                },
                actions = {
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.Share, contentDescription = "导出 Markdown")
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
                .padding(padding),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.l),
            ) {
                Spacer(Modifier.height(Spacing.m))

                Text(
                    text = title.ifBlank { "（无标题）" },
                    fontSize = 20.sp,
                    lineHeight = 29.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = createdLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )

                Spacer(Modifier.height(Spacing.l))

                if (body.isNotBlank()) {
                    Text(
                        text = body,
                        fontSize = 15.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 已追问过就显示轮次摘要，不显示输入
                val answered = rounds.filter { it.answered }
                if (answered.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xl))
                    Text(
                        // 必须取 .size。直接插值 List 会把整个 toString() 打进界面——
                        // 之前追问从来没成功过、rounds 恒为空，
                        // 所以这一行直到第一次真正聊完才暴露出问题。
                        text = "已追问 ${answered.size} 轮",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(Spacing.s))
                    // **全部都列出来，不是只留最后两条。**
                    // 只留两条会让人觉得「聊过的记录丢了」
                    answered.forEach { r ->
                        Row(
                            Modifier.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                Modifier
                                    .padding(top = 6.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.size(Spacing.s))
                            Text(
                                text = r.answer.orEmpty(),
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 发过芽 → 给一句摘要，点按钮能回去看
                if (latestSproutTitle.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.xl))
                    Text(
                        text = "长出来的",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = latestSproutTitle,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(Spacing.xxl))
            }

            // 底部操作区：并列的入口，没有先后
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(Spacing.l),
            ) {
                if (hasArticle) {
                    OutlinedButton(
                        onClick = onViewArticle,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("看这篇文章")
                    }
                    Spacer(Modifier.height(Spacing.s))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("编辑", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = onSprout,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(sproutLabel, fontSize = 13.sp)
                    }
                    Button(
                        onClick = onDeepen,
                        modifier = Modifier.weight(1.4f),
                    ) {
                        Text(if (hasDeepened) "继续聊" else "AI 追问", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
