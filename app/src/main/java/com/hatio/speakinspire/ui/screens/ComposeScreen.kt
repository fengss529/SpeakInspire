package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.data.model.Paragraph
import com.hatio.speakinspire.service.DraftStyle
import com.hatio.speakinspire.ui.compose.ComposeChip
import com.hatio.speakinspire.ui.compose.ComposeUiState
import com.hatio.speakinspire.ui.compose.RejectedInfo
import com.hatio.speakinspire.ui.text.SelectableText
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
    /**
     * 能不能「回去再聊」。
     *
     * 文章独立之后，它可能压根没有来源记录（记录被删了），
     * 这时那个按钮点了也没有目的地——干脆不显示，
     * 好过点一下什么都不发生。
     */
    canReopenDeepening: Boolean = true,
    /** 转成拍摄选题。文章存好之后才给这个入口 */
    onConvert: () -> Unit = {},
    /** 把这一篇发出去（单条 md → 分享面板）。只在文章已存进库里时可用 */
    onShare: () -> Unit = {},
    onSettings: () -> Unit = {},
    /** 勾掉/勾上一块素材来源 */
    onToggleChip: (ComposeChip) -> Unit = {},
    /** 这次换一种风格。只对当前这篇生效，不写回设置 */
    onSelectStyle: (DraftStyle) -> Unit = {},
) {
    // 风格选择弹窗的显隐。放在界面层是因为它没有跨页面状态：
    // 关掉弹窗不需要记住任何东西，选中的结果直接回写到 ViewModel
    var stylePickerOpen by remember { mutableStateOf(false) }

    if (stylePickerOpen) {
        StylePicker(
            current = state.style,
            onPick = {
                onSelectStyle(it)
                stylePickerOpen = false
            },
            onDismiss = { stylePickerOpen = false },
        )
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
                    Text(
                        text = if (state.alreadyComposed) "文章" else "成文章",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    if (state.hasContent && state.alreadyComposed) {
                        // 分享 ≠ 导出。
                        // 导出是「留一份在自己手里」，统一在设置页写进下载目录；
                        // 这个图标是「把这一篇发出去」——文章本来就是给外面看的，
                        // 所以它留在文章这一页，而不是塞进设置里
                        IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, contentDescription = "分享")
                        }
                    }
                    if (state.hasContent) {
                        TextButton(onClick = onToggleSource) {
                            Text(
                                text = if (state.sourceMode) "看纯文章" else "看来源",
                                fontSize = 12.sp,
                            )
                        }
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
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize()) {
                    // 素材来源与风格。
                    //
                    // **放在正文之前**，因为它在「还没成文」的时候就该看得见：
                    // 用户需要先知道「这次会用哪几样东西写」，
                    // 而不是等文章出来了再回头猜。
                    if (!state.loading) {
                        ComposeChips(
                            state = state,
                            onToggle = onToggleChip,
                            onOpenStyle = { stylePickerOpen = true },
                        )
                    }
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
                }
            }

            // 改过风格或勾选之后，正文和设置对不上了。
            //
            // 只提示、不自动重写：一次成文是一次真实调用，
            // 点一下芯片就烧一次额度、等十几秒，那不是点一下该有的代价
            if (state.needsRecompose && state.hasContent && !state.loading) {
                StaleBanner(onRecompose = onRetry)
            }

            if (state.hasContent && !state.loading) {
                // 已经在思考库里 → 这是**看文章**，不是「再存一次」。
                //
                // 真机反馈原话：「在思考里选择文章，最下面还是会出现
                // 『存进思考库』的按钮，逻辑不对」。
                // 对——文章已经在库里了，再摆一个保存按钮，
                // 用户会怀疑自己刚才点的是什么、是不是又多存了一份。
                if (state.alreadyComposed) {
                    ArticleActions(
                        onConvert = onConvert,
                        onReopenDeepening = onGoBackToDeepening,
                        showReopen = canReopenDeepening,
                        styleLabel = if (state.styleRecorded) state.style.label else null,
                    )
                } else {
                    SaveBar(
                        showSourceNote = state.sourceMode &&
                            (state.aiCount > 0 || state.sproutParaCount > 0),
                        mineCount = state.mineCount,
                        aiCount = state.aiCount,
                        sproutCount = state.sproutParaCount,
                        // 说的是**正文**用的风格，不是 chip 上刚选的那个
                        styleLabel = state.writtenStyle.label,
                        onSave = onSave,
                    )
                }
            }
        }
    }
}

/**
 * 这一行：用哪种风格写 + 拿哪几样东西写。
 *
 * ## 为什么风格和素材放在一起
 *
 * 它们回答的是同一个问题：「这篇会怎么写出来」。
 * 分开摆会让用户以为风格是外观、素材是内容，其实两者都在
 * 决定正文的样子。
 *
 * ## 为什么默认全勾
 *
 * 产品说法是「综合记录、回答、发芽」。默认不勾，那句说法就是假的。
 * 留开关是因为芽里混着别人的话——有人不希望它们进自己的文章，
 * 那是他的权利。
 */
@Composable
private fun ComposeChips(
    state: ComposeUiState,
    onToggle: (ComposeChip) -> Unit,
    onOpenStyle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 风格：带一个下拉箭头，暗示「点开还有别的」
        AssistChip(
            onClick = onOpenStyle,
            label = {
                Text(
                    text = "${state.style.label} ▾",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )

        if (state.hasDialogue) {
            SourceChip(
                label = "追问 ${state.roundCount} 轮",
                checked = state.includeDialogue,
                onClick = { onToggle(ComposeChip.DIALOGUE) },
            )
        }
        if (state.hasSprouts) {
            SourceChip(
                label = "发芽 ${state.sproutCount} 颗",
                checked = state.includeSprouts,
                onClick = { onToggle(ComposeChip.SPROUTS) },
            )
        }
        // 「记录原文」永远在。三个都取消等于让模型凭空写一篇，
        // 所以 ViewModel 里也拦了「取消最后一项」这个动作
        SourceChip(
            label = "记录原文",
            checked = state.includeNote,
            onClick = { onToggle(ComposeChip.NOTE) },
        )
    }
}

@Composable
private fun SourceChip(label: String, checked: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = checked,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontSize = 12.sp,
                color = if (checked) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.outline,
            )
        },
    )
}

/**
 * 风格选择。
 *
 * 每一行给「写出来是什么样」和「什么时候该用」，而不只是名字——
 * 「观点文」三个字对不同的人意味着不同的东西，而 blurb 那句
 * 是他真正用来判断的依据。
 */
@Composable
private fun StylePicker(
    current: DraftStyle,
    onPick: (DraftStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这一篇怎么写", fontSize = 16.sp) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                DraftStyle.entries.forEach { style ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onPick(style) }
                            .padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = style == current,
                            onClick = { onPick(style) },
                        )
                        Column(Modifier.padding(start = Spacing.xs)) {
                            Text(
                                text = style.label,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = style.blurb,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "适合：${style.bestFor}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.s))
                // 说清「这次」和「默认」的区别，否则用户会以为设置页那个没用了
                Text(
                    text = "换只对现在这一篇生效，设置页里的默认风格不变。",
                    fontSize = 10.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("不改了", fontSize = 13.sp) }
        },
    )
}

/** 风格/勾选换过之后，正文已经和当前设置对不上 */
@Composable
private fun StaleBanner(onRecompose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f))
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "这篇还是按刚才的设置写的。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRecompose) {
            Text("按新的重写", fontSize = 12.sp)
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
 * ## 三档，不是两档
 *
 * | 竖线 | 标签 | 是什么 |
 * |---|---|---|
 * | 实心紫 | 你说的 | 用户在记录或回答里说过的 |
 * | 实心蓝 | AI 补 · 含他人观点 | 来自「发芽」——别人的说法、例子、引用 |
 * | 浅暖色 | AI 补 | 为了文章通顺补的论证 |
 *
 * 第二档必须单独存在。芽里装的是别人的东西，混进「AI 补」会让用户
 * 以为那只是通用论证，混进「你说的」就是造假。
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
        // 左侧竖线：实心=你的话，蓝色=他人的话，浅色=AI 补
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.extraSmall)
                .background(
                    when {
                        !showSource -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        para.mine -> StatusColors.mine
                        para.fromSprout -> StatusColors.topic
                        else -> StatusColors.aiFill
                    },
                ),
        )

        Spacer(Modifier.width(Spacing.m))

        Column(Modifier.weight(1f)) {
            if (showSource) {
                Text(
                    text = para.sourceLabel,
                    fontSize = 10.sp,
                    color = when {
                        para.mine -> StatusColors.mine
                        para.fromSprout -> StatusColors.topic
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
                Spacer(Modifier.height(3.dp))
            }
            // 文章是要拿出去用的：选一段复制去改、去发，
            // 是这个页面最自然的动作
            SelectableText(
                text = para.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

@Composable
private fun SaveBar(
    showSourceNote: Boolean,
    mineCount: Int,
    aiCount: Int,
    sproutCount: Int,
    styleLabel: String,
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
                text = buildString {
                    append("这篇里有 $mineCount 段是你说的，$aiCount 段是 AI 补的论证")
                    // 有芽的段落单独报一句。它们和「AI 补」不是一类东西，
                    // 合并成一个数字会让用户以为文章里没有别人的话
                    if (sproutCount > 0) append("，$sproutCount 段来自你长过的芽")
                    append("。存下来会一直留在思考库里。")
                },
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(Spacing.s))
        }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text("存进思考库")
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "按「$styleLabel」写的。存完之后想转选题、想接着聊，都在下一个页面。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 已在思考库里的文章——这里是**动作组**，不是「下一步」。
 *
 * ## 为什么不再是单个按钮
 *
 * 这个页面以前是流水线的一环：存文章 → 转选题 → 拍摄。
 * 于是「已经存过的文章」下面还挂着「存进思考库」，
 * 点它什么都不会发生（upsert 同一行），用户只能怀疑按钮坏了。
 *
 * 现在两个动作并列，代表这篇**已经存在**的文章可以往哪儿去：
 * 转成一条要拍的选题，或者回去接着聊。
 * 也可以什么都不做，直接返回——它是资产，不需要被消费掉。
 *
 * ## 为什么去掉了「导出 .md」
 *
 * 那个按钮导出的是**这一篇**。用户点它时的真实想法是
 * 「我得留个备份」，而不是「我要把这一篇发给谁」——
 * 备份应该是**全部一起**去一个看得见的目录，
 * 而不是在每一篇文章里点一次、再选一次存到哪儿。
 *
 * 现在导出统一在设置页：一次把 data.json 和全量 md 写进 `下载/随手记/`。
 * 单条内容的分享入口留在记录详情页的图标上，那里才是「把这一条发出去」的语境。
 */
@Composable
private fun ArticleActions(
    onConvert: () -> Unit,
    onReopenDeepening: () -> Unit,
    showReopen: Boolean,
    /** 库里记过风格才有值。没记过的老文章不硬说它是哪种 */
    styleLabel: String? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(Spacing.l),
    ) {
        Text(
            text = if (styleLabel != null) "这是篇$styleLabel，已经在思考库里了。"
            else "已经存在思考库里了。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(Spacing.s))
        Button(onClick = onConvert, modifier = Modifier.fillMaxWidth()) {
            Text("转成一条选题")
        }
        Spacer(Modifier.height(Spacing.s))
        // 没有来源记录时（记录已删），「回去再聊」没有目的地
        if (showReopen) {
            OutlinedButton(
                onClick = onReopenDeepening,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("回去再聊", fontSize = 13.sp)
            }
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