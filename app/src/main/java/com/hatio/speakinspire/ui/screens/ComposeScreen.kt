package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.ui.compose.ComposeUiState
import com.hatio.speakinspire.ui.compose.RejectedInfo
import com.hatio.speakinspire.ui.theme.Spacing
import com.hatio.speakinspire.ui.theme.StatusColors

/**
 * 成文页 —— 以及「来源」视图。
 *
 * ## 来源视图不是可选功能，是「允许 AI 补论证」的配套
 *
 * 成文阶段 AI 可以补充论证（重组、润色、举例）。
 * 如果不标出来，用户无法分辨哪句是自己说的、哪句是 AI 加的——
 * 那「允许补」就等于「可以编」。
 *
 * 所以每段左侧有一条竖线：
 * - 实线深色 = 你说过的
 * - 虚线浅色 = AI 补的
 *
 * 右上角的开关可以切到「纯文章」视图，标注全部隐藏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    state: ComposeUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleSource: () -> Unit,
    onSave: () -> Unit,
    onGoBackToDeepening: () -> Unit,
) {
    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text("成文章", style = MaterialTheme.typography.titleMedium)
                },
                actions = {
                    if (state.hasContent) {
                        TextButton(onClick = onToggleSource) {
                            Text(
                                text = if (state.sourceMode) "看纯文章" else "看来源",
                                fontSize = 12.sp,
                            )
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
            Box(Modifier.weight(1f)) {
                when {
                    state.loading -> CenterBox { LoadingBlock() }

                    state.rejected != null -> CenterBox {
                        RejectedBlock(state.rejected, onGoBackToDeepening)
                    }

                    state.errorMessage != null -> CenterBox {
                        ErrorBlock(state.errorMessage, onRetry)
                    }

                    state.hasContent -> ArticleBody(state)

                    else -> CenterBox { NoContentBlock(onRetry) }
                }
            }

            if (state.hasContent && !state.loading) {
                SaveBar(
                    showSourceNote = state.sourceMode && state.aiCount > 0,
                    mineCount = state.mineCount,
                    aiCount = state.aiCount,
                    onSave = onSave,
                )
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 文章正文。
 *
 * 段落左侧的竖线是来源标记：
 * - 你的话：实线，紫色
 * - AI 补的：虚线，暖色底
 */
@Composable
private fun ArticleBody(state: ComposeUiState) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l),
    ) {
        Spacer(Modifier.height(Spacing.m))

        Text(
            text = state.title,
            fontSize = 21.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(Spacing.xl))

        state.paragraphs.forEach { para ->
            MarkedParagraph(
                para = para,
                showSource = state.sourceMode,
            )
            Spacer(Modifier.height(Spacing.l))
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

/**
 * 带来源标记的段落。
 *
 * 竖线的样式区分是**唯一**需要用户注意的视觉差异——
 * 太花哨会让人忽略内容本身。
 */
@Composable
private fun MarkedParagraph(para: Paragraph, showSource: Boolean) {
    // IntrinsicSize.Min 让竖线高度跟随文字高度——
    // 之前写死高度会导致长段落时竖线不够长
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        // 左侧竖线：实心=你的话，浅色=AI 补
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.extraSmall)
                .background(
                    when {
                        !showSource -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        para.mine -> StatusColors.mine
                        else -> StatusColors.aiFill
                    },
                ),
        )

        Spacer(Modifier.width(Spacing.m))

        Column(Modifier.weight(1f)) {
            if (showSource) {
                Text(
                    text = if (para.mine) "你说的" else "AI 补",
                    fontSize = 10.sp,
                    color = if (para.mine) StatusColors.mine
                    else MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(3.dp))
            }
            Text(
                text = para.text,
                fontSize = 15.sp,
                lineHeight = 26.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SaveBar(
    showSourceNote: Boolean,
    mineCount: Int,
    aiCount: Int,
    onSave: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(Spacing.l),
    ) {
        if (showSourceNote) {
            Text(
                text = "这篇里有 $mineCount 段是你说的，$aiCount 段是 AI 补的论证。" +
                    "存下来会一直留在思考库里。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(Spacing.s))
        }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text("存进思考库")
        }
    }
}

@Composable
private fun LoadingBlock() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.height(36.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(Spacing.l))
        Text(
            text = "在整理你说的话…",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "不会加入你没说过的东西",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 拒稿提示。
 *
 * 关键：**不说「你还没说够」**。那会让人觉得是自己的问题。
 * 而是给出路——「再多聊几句你当时怎么想的」，把门槛说清楚。
 */
@Composable
private fun RejectedBlock(info: RejectedInfo, onGoBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "还差一点", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Spacing.m))
        Text(
            text = info.message,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "现在有 ${info.actualChars} 字，还差大约 ${info.gap} 字。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(Spacing.xl))
        Button(onClick = onGoBack) {
            Text("回去再聊聊")
        }
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "没写成",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.m))
        Text(
            text = message,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
        OutlinedButton(onClick = onRetry) {
            Text("再试一次")
        }
    }
}

@Composable
private fun NoContentBlock(onCompose: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "还没开始", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Spacing.m))
        Text(
            text = "把你说过的话整理成一篇可以讲出来的稿子。",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
        Button(onClick = onCompose) {
            Text("开始整理")
        }
    }
}