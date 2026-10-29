package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.data.model.Round
import com.hatio.speakinspire.ui.components.EditField
import com.hatio.speakinspire.ui.theme.Spacing
import com.hatio.speakinspire.ui.theme.StatusColors

/**
 * 记录条详情页 —— 这条记录的控制台，也是唯一能看到「当时聊了什么」的地方。
 *
 * ## 为什么每一段都能直接点进去改
 *
 * 原来这页是只读的，改一个字要先点「编辑」跳到另一个页面。
 * 麻烦的不是多一步，是**跳走就看不见上下文**：想改第三轮的回答，
 * 得先记住自己当时在回答什么，再在那个只有正文的编辑页里改。
 *
 * 现在标题、正文、每条回答各自可编辑，点哪改哪，改完自动存。
 * 没有「编辑态」、没有保存按钮——保存不该是用户的活。
 *
 * ## 问答为什么要分两套样式
 *
 * 问题（AI 说的）和回答（自己说的）必须一眼分得开，
 * 否则整页会变成一大片一样的字，读的人得靠介词判断谁在说话。
 * 问题用左侧竖线 + 浅底 + 小一号字；回答用正文字号、不加任何底。
 *
 * 未答的问题也画出来，标一个「没答」——那个空位本身就是信息，
 * 它标出你卡在哪。但**不给就地补答**：追问页有完整上下文和选句工具，
 * 在这里徒手补一段，等于在没有上下文的地方生产素材。
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
    saveError: String? = null,
    articleOutdated: Boolean = false,
    onTitleChange: (String) -> Unit = {},
    onBodyChange: (String) -> Unit = {},
    onAnswerChange: (Int, String) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    onDeepen: () -> Unit,
    onSprout: () -> Unit = {},
    onViewArticle: () -> Unit,
    /** 按现在的回答重新成文 */
    onRecompose: () -> Unit = {},
    onSettings: () -> Unit = {},
    onShare: () -> Unit = {},
    onDismissMessage: () -> Unit = {},
    onDismissSaveError: () -> Unit = {},
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
                    IconButton(onClick = onShare) {
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

                // 标题：点一下就能改
                EditField(
                    value = title,
                    onValueChange = onTitleChange,
                    placeholder = "无标题",
                    style = TextStyle(
                        fontSize = 20.sp,
                        lineHeight = 29.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )

                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = createdLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )

                Spacer(Modifier.height(Spacing.l))

                // 正文：同上。留空是合法的——有些记录就只有一句标题
                EditField(
                    value = body,
                    onValueChange = onBodyChange,
                    placeholder = "写下你想说的",
                    style = TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )

                if (saveError != null) {
                    Spacer(Modifier.height(Spacing.s))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = saveError,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissSaveError) {
                            Text("知道了", fontSize = 11.sp)
                        }
                    }
                }

                if (rounds.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xl))
                    QaSection(rounds = rounds, onAnswerChange = onAnswerChange)
                }

                if (articleOutdated) {
                    Spacer(Modifier.height(Spacing.l))
                    OutdatedHint(
                        rounds = rounds.count { it.answered },
                        onRecompose = onRecompose,
                    )
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
                        onClick = onSprout,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(sproutLabel, fontSize = 13.sp)
                    }
                    Button(
                        onClick = onDeepen,
                        modifier = Modifier.weight(1.6f),
                    ) {
                        Text(if (hasDeepened) "继续聊" else "AI 追问", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * 问答区。
 *
 * 全部列出来，不是只留最后两条——只留两条会让人觉得
 * 「聊过的记录丢了」。轮次编号给到每一轮，方便回头说「第二问那句」。
 */
@Composable
private fun QaSection(rounds: List<Round>, onAnswerChange: (Int, String) -> Unit) {
    val answered = rounds.count { it.answered }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "追问记录",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(Spacing.xs))
        // 必须取数字。直接插值 List 会把整个 toString() 打进界面——历史上真发生过
        Text(
            text = if (answered == rounds.size) "$answered 轮" else "$answered / ${rounds.size} 轮",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    Spacer(Modifier.height(Spacing.s))

    rounds.forEachIndexed { index, round ->
        if (index > 0) Spacer(Modifier.height(Spacing.l))
        RoundBlock(
            index = index,
            round = round,
            onAnswerChange = { onAnswerChange(index, it) },
        )
    }
}

@Composable
private fun RoundBlock(index: Int, round: Round, onAnswerChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            // 问题：左侧竖线 + 浅底。视觉上就是「别人在说」。
            // 用 IntrinsicSize.Min 让竖线跟着文字高度走——
            // 写死高度会在长问题上戳出一截，在短问题下拖一条尾巴
            Row(
                Modifier
                    .weight(1f)
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(StatusColors.ongoing, RoundedCornerShape(1.dp)),
                )
                Column(
                    Modifier.padding(
                        start = Spacing.s,
                        end = Spacing.s,
                        top = Spacing.s,
                        bottom = Spacing.s,
                    ),
                ) {
                    Text(
                        text = "问 ${index + 1}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = round.question,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!round.answered) {
                Spacer(Modifier.width(Spacing.s))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "没答",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        if (round.answered) {
            Spacer(Modifier.height(Spacing.s))
            // 回答：正文字号、无底无边。视觉上就是「我在说」
            EditField(
                value = round.answer.orEmpty(),
                onValueChange = onAnswerChange,
                placeholder = "",
                style = TextStyle(
                    fontSize = 15.sp,
                    lineHeight = 25.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.padding(start = Spacing.s),
            )
        }
    }
}

/** 文章比回答旧了 */
@Composable
private fun OutdatedHint(rounds: Int, onRecompose: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(StatusColors.aiFill)
            .padding(Spacing.m),
    ) {
        Text(
            text = "这篇文章是按当时的回答写的，之后你改过回答。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "现在有 $rounds 轮回答可用。重新成文会覆盖当前这一篇。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xs))
        TextButton(
            onClick = onRecompose,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text("按新素材重新成文", fontSize = 12.sp)
        }
    }
}
