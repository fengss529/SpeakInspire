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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.ui.theme.Spacing
import com.hatio.speakinspire.ui.theme.StatusColors

/**
 * 记录条详情页。
 *
 * ## 为什么要有这一页
 *
 * 之前点开记录条直接进追问页，导致：
 * - 用户看不到自己到底记了什么
 * - 想改个字都做不到，只能重新记一条
 * - 不知道该做什么操作——是输入还是等 AI 问？
 *
 * 这一页把「看清内容」和「决定下一步」分开。
 *
 * ## 两个按钮的取舍
 *
 * **编辑** —— 随手记的内容常常只写了半句，回头补是常态。
 * 不给编辑就等于强迫用户重新记一条。
 *
 * **AI 追问** —— 主按钮。它是App 的核心动作。
 *
 * 不放输入框：那个输入框在追问页已经有了，
 * 在这里再放一个会让用户以为可以直接和 AI 说话——
 * 但追问阶段 AI 只提问不给答案，直接说没有意义。
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
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeepen: () -> Unit,
    onViewArticle: () -> Unit,
) {
    Scaffold(
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
                if (rounds.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xl))
                    Text(
                        text = "已追问 $rounds 轮",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(Spacing.s))
                    rounds.filter { it.answered }.takeLast(2).forEach { r ->
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
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.xxl))
            }

            // 底部操作区
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(Spacing.l),
            ) {
                // 已成文的先给看文章的入口
                if (hasArticle) {
                    OutlinedButton(
                        onClick = onViewArticle,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("看这篇文章")
                    }
                    Spacer(Modifier.height(Spacing.s))
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("编辑")
                    }
                    Button(
                        onClick = onDeepen,
                        modifier = Modifier.weight(1.4f),
                    ) {
                        Text(if (hasDeepened) "继续追问" else "AI 追问")
                    }
                }
            }
        }
    }
}