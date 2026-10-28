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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.ui.Stats
import com.hatio.speakinspire.ui.components.EmptyHint
import com.hatio.speakinspire.ui.theme.Spacing
import com.hatio.speakinspire.ui.theme.StatusColors

/**
 * 统计页。
 *
 * ## 为什么删掉了那张「转化漏斗」
 *
 * 原来漏斗拿「记录总数」当分母，算「有多少记录拍成了视频」。
 * 三种资源独立之后这个数字没有意义了：一条记录可能压根没成文，
 * 而它的文章早就变成了选题被别人拍了。**跨资源做除法只会
 * 算出一个谁也解释不清的数。**
 *
 * 现在每一项只描述自己：记录有多少成了文、文章有多少转了选题、
 * 选题拍了几条。三条链各自自洽，用户看一眼就知道卡在哪一段。
 *
 * ## 为什么不显示「追问轮数」
 *
 * 停留时长受打字速度影响，是伪指标。真正有意义的是走到哪一步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatScreen(
    stats: Stats,
    pendingCount: Int,
    onSettings: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("统计", style = MaterialTheme.typography.titleLarge) },
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
        if (stats.recorded == 0 && stats.articles == 0 && stats.topics == 0) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyHint(
                    icon = "▦",
                    title = "还没有数据",
                    hint = "记几条想法之后，这里会显示三类资源各自的情况。",
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l),
        ) {
            Spacer(Modifier.height(Spacing.l))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BigNumber(stats.recorded, "记录", StatusColors.wait)
                BigNumber(stats.articles, "文章", StatusColors.articlEd)
                BigNumber(stats.topics, "选题", StatusColors.topic)
            }

            if (stats.streak > 0) {
                Spacer(Modifier.height(Spacing.m))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(StatusColors.aiFill)
                        .padding(Spacing.m),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "连续记录 ${stats.streak} 天",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            Section("记录") {
                StatRow("一共记下", stats.recorded)
                StatRow("追问收尾", stats.deepened)
                StatRow("已写成文章", stats.composed, StatusColors.articlEd)
                ProgressRow("成文比例", stats.composeRate, StatusColors.articlEd)
                if (pendingCount > 0) {
                    Hint("另有 $pendingCount 条深化完了还没成文——这一环流失最多。")
                }
            }

            Section("文章") {
                StatRow("一共写下", stats.articles)
                StatRow("转过选题", stats.articlesWithTopic, StatusColors.topic)
            }

            Section("选题") {
                StatRow("一共转了", stats.topics)
                StatRow("待开拍", stats.todo, StatusColors.topic)
                StatRow("已拍摄", stats.shot, StatusColors.shot)
                if (stats.parked > 0) StatRow("搁置", stats.parked, StatusColors.parked)
                ProgressRow("拍摄进度", stats.shotRate, StatusColors.shot)
            }

            Spacer(Modifier.height(Spacing.xl))

            Text(
                text = "口径说明",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = "三类资源各自独立，所以每一项只在自己这一类里算比例。" +
                    "「成文比例」的分母是记录，「拍摄进度」的分母是选题，" +
                    "两个数字不能互相代入。",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(Spacing.s))
    content()
    Spacer(Modifier.height(Spacing.xl))
}

@Composable
private fun StatRow(label: String, value: Int, color: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$value",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProgressRow(label: String, pct: Int?, color: Color) {
    // 分母为 0（null）：这一项算不出来，画个「—」就好。
    // 硬画一条 0% 的空槽会让人以为「我做了很多但没一条成功」——
    // 实际是「分母根本没有」，那是两件完全不同的事。
    if (pct == null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "—",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        return
    }

    Column(Modifier.padding(top = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$pct%",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        // 0% 时连槽都不画。
        //
        // 只画一条空槽的话，它在视觉上就是一条 8dp 的灰色横线——
        // 和页面上其他用来分隔段落的线没有区别，读起来像「这里断了」。
        // 比例本身已经用文字写清楚了，槽只在真有进度时才有信息量。
        if (pct > 0) {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((pct.coerceIn(2, 100)) / 100f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(Spacing.s))
    Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BigNumber(value: Int, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            fontSize = 32.sp,
            fontWeight = FontWeight.Light,
            color = color,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
