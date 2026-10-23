package com.hatio.speakinspire.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.data.model.Sprout
import com.hatio.speakinspire.data.model.SproutSection
import com.hatio.speakinspire.ui.sprout.SproutUiState
import com.hatio.speakinspire.ui.theme.Spacing

/**
 * 发芽页 —— 从一条记录里长出新的连接。
 *
 * ## 这一页和追问页的气质必须不同
 *
 * 追问页是**紧的**：左问右答、气泡、输入框，一直在催他说话。
 * 发芽页必须是**松的**：大段留白、句子短、没有输入框。
 * 因为这里要做的事是「让他安静地看一眼别人怎么想」，
 * 摆一个输入框在下面，他会以为又要他说点什么。
 *
 * ## 每一节的三段式
 *
 * 你说的（种子，灰底小字）→ 长出来的（正文）→ 出处（若无可不显示）。
 *
 * 顺序不能反。先回到他自己的话，这一节才不是一句无根的引用；
 * 而且这样他一眼就能看见「这是从我这句话长出去的」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SproutScreen(
    state: SproutUiState,
    onBack: () -> Unit,
    onSprout: () -> Unit,
    onSettings: () -> Unit,
    /** 顺着某一节再记一条。参数是那一节的 insight，作为新记录的正文 */
    onSeedNewNote: (String) -> Unit,
    /** 翻看以前的芽 */
    onReview: (Sprout) -> Unit,
    onDismissError: () -> Unit,
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
                    Column {
                        Text("发芽", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "从这句话往外长",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                },
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // 发芽页底部没有输入框，但「接着记一条」会跳走，
                // 这里加 imePadding 只是为了将来万一加输入时不再踩同一个坑
                .imePadding(),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Spacing.l,
                    end = Spacing.l,
                    top = Spacing.s,
                    bottom = Spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                // 种子
                item("seed") {
                    SeedCard(title = state.noteTitle, body = state.noteBody)
                }

                // 历次报告切换
                if (state.existing.size > 1) {
                    item("history") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        ) {
                            state.existing.forEachIndexed { i, sp ->
                                val active = sp.id == state.current?.id
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (active) {
                                                MaterialTheme.colorScheme.primary
                                                    .copy(alpha = 0.12f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                                    .copy(alpha = 0.35f)
                                            }
                                        )
                                        .clickable { onReview(sp) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                ) {
                                    Text(
                                        text = "第 ${state.existing.size - i} 次",
                                        fontSize = 11.sp,
                                        color = if (active) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // 报告 / 未发芽 / 生成中
                val report = state.current
                when {
                    state.busy -> item("busy") { SproutingBlock() }

                    report != null -> {
                        item("title") {
                            Text(
                                text = report.title,
                                fontSize = 19.sp,
                                lineHeight = 28.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = Spacing.s),
                            )
                        }
                        report.sections.forEachIndexed { i, section ->
                            item("sec_${report.id}_$i") {
                                SproutSectionBlock(
                                    index = i + 1,
                                    section = section,
                                    onSeed = { onSeedNewNote(section.insight) },
                                )
                            }
                        }
                    }

                    else -> item("empty") {
                        IdleBlock(onSprout = onSprout)
                    }
                }

                state.error?.let { msg ->
                    item("error") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                )
                                .padding(Spacing.m),
                        ) {
                            Text(
                                text = msg,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Row {
                                TextButton(onClick = onSprout) { Text("再试一次", fontSize = 13.sp) }
                                TextButton(onClick = onDismissError) {
                                    Text("知道了", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 底部动作区
            if (!state.busy) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.l, vertical = Spacing.m),
                ) {
                    if (state.hasReport) {
                        OutlinedButton(
                            onClick = onSprout,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("再发芽一次")
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = "每次长出来的东西不一样。旧的都留着，不覆盖。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    } else {
                        Button(onClick = onSprout, modifier = Modifier.fillMaxWidth()) {
                            Text("发芽一下")
                        }
                    }
                }
            }
        }
    }
}

/** 种子卡：他记下的那句话 */
@Composable
private fun SeedCard(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(Spacing.m),
    ) {
        Text(
            text = "种子",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        if (title.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 报告的一节。
 *
 * 左侧竖线 + 编号，是为了让三节看起来是**并列的三条路**，
 * 而不是一篇有先后的文章——他随时可以只看其中一节。
 */
@Composable
private fun SproutSectionBlock(
    index: Int,
    section: SproutSection,
    onSeed: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.s)
            // IntrinsicSize.Min：竖线高度跟随这一节的文字高度。
            // 写死 dp 会在 insight 短的时候拖出一条空白，
            // 长的时候竖线又不够长
            .height(IntrinsicSize.Min),
    ) {
        // 竖线
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        )
        Spacer(Modifier.width(Spacing.m))

        Column(Modifier.weight(1f)) {
            // 他的原话
            if (section.seed.isNotBlank()) {
                Text(
                    text = "“${section.seed}”",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(Spacing.s))
            }

            // 长出来的
            Text(
                text = section.insight,
                fontSize = 14.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // 出处。没有就不画——留空比编一个强
            if (section.hasSource) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "—— ${section.source}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "接着这句记一条",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onSeed)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            Spacer(Modifier.height(Spacing.s))
        }
    }
}

/** 还没发芽 */
@Composable
private fun IdleBlock(onSprout: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xl, bottom = Spacing.m),
    ) {
        Text(
            text = "这句话放到别处去看，是什么样子？",
            fontSize = 15.sp,
            lineHeight = 24.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = "会有人从这句话出发，去找别的领域里相似的想法、它成立的条件、" +
                "一个能反照它的例子。给的是参照物，不是结论——" +
                "看完怎么想，还是你的事。",
            fontSize = 13.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SproutingBlock() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.height(30.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(Spacing.l))
        Text(
            text = "在找别处相似的想法…",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "找不到出处的地方，会老实留空",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
