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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.domain.DayLog
import com.hatio.speakinspire.ui.Stats
import com.hatio.speakinspire.ui.components.NoteCalendar
import com.hatio.speakinspire.ui.components.StatCard
import com.hatio.speakinspire.ui.components.StatNumber
import com.hatio.speakinspire.ui.theme.Spacing
import com.hatio.speakinspire.ui.theme.StatusColors
import java.time.LocalDate
import java.time.YearMonth

/**
 * 统计页。
 *
 * ## 排序即优先级：日历在最上面
 *
 * 这一页原来开头是三个大数字，接着是三段「标签 + 数值」的行，
 * 末尾还有一段讲口径的说明。每一样都对，但读起来像报表——
 * 而用户来这儿不是来看报表的，是来看「我最近怎么样」。
 *
 * 现在日历吃掉第一屏，数字缩到 24sp 让位。理由很简单：
 * 「记了 42 条」是存量，看不出节奏；日历能一次回答
 * 「有没有在记、断在哪、哪天特别多」。
 *
 * ## 为什么删掉那段口径说明
 *
 * 它原本是为了解释「跨资源漏斗为什么被删掉」——那是给
 * 开发者和设计讨论看的，用户不需要读设计理由。现在缩成一行脚注。
 *
 * ## 为什么「当前连续」和「最长」一起给
 *
 * 只有当前连续的时候，断一天就归零，那个数字会变成一种指责。
 * 最长那个不会归零，它记着「你做到过」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatScreen(
    stats: Stats,
    pendingCount: Int,
    log: DayLog,
    onSettings: () -> Unit = {},
    today: LocalDate = LocalDate.now(),
) {
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l),
        ) {
            Spacer(Modifier.height(Spacing.s))

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .padding(Spacing.s),
            ) {
                NoteCalendar(
                    month = month,
                    log = log,
                    today = today,
                    selected = selected,
                    onSelectMonth = {
                        month = it
                        // 换了月份，之前选中的日子不在这一屏里了，
                        // 留着那句话只会让人以为它属于新月份
                        selected = null
                    },
                    onSelectDay = { selected = if (selected == it) null else it },
                )
            }

            Spacer(Modifier.height(Spacing.m))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatNumber(stats.recorded, "记录", StatusColors.wait)
                StatNumber(stats.articles, "文章", StatusColors.articlEd)
                StatNumber(stats.topics, "选题", StatusColors.topic)
            }

            if (stats.streak > 0 || stats.longestStreak > 0) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = streakLine(stats.streak, stats.longestStreak),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            Spacer(Modifier.height(Spacing.l))

            StatCard("记录", StatusColors.wait) {
                HeadLine(stats.recorded, "条")
                DimLine("追问收尾 ${stats.deepened} · 已成文 ${stats.composed}")
                RateLine("成文比例", stats.composeRate, StatusColors.articlEd)
                if (pendingCount > 0) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = "另有 $pendingCount 条聊完了还没成文——这一环流失最多。",
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.s))

            StatCard("文章", StatusColors.articlEd) {
                HeadLine(stats.articles, "篇")
                DimLine("转过选题 ${stats.articlesWithTopic} 篇")
            }

            Spacer(Modifier.height(Spacing.s))

            StatCard("选题", StatusColors.topic) {
                HeadLine(stats.topics, "个")
                val parts = buildList {
                    add("待开拍 ${stats.todo}")
                    add("已拍摄 ${stats.shot}")
                    if (stats.parked > 0) add("搁置 ${stats.parked}")
                }
                DimLine(parts.joinToString(" · "))
                RateLine("拍摄进度", stats.shotRate, StatusColors.shot)
            }

            Spacer(Modifier.height(Spacing.l))

            Text(
                text = "三类资源各自独立，每一项只在自己那一类里算比例。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

private fun streakLine(current: Int, longest: Int): String = when {
    current == 0 && longest == 0 -> ""
    current == longest -> "连续记录 $current 天"
    else -> "当前连续 $current 天 · 最长 $longest 天"
}

@Composable
private fun HeadLine(value: Int, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value.toString(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = unit,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun DimLine(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 比例那一行。
 *
 * 分母为 0 时算不出来，显示「—」。硬画一条 0% 的空槽会让人
 * 以为「我做了很多但一条没成」——实际是分母根本没有，那是两件事。
 */
@Composable
private fun RateLine(label: String, pct: Int?, color: Color) {
    Spacer(Modifier.height(Spacing.xs))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = pct?.let { "$it%" } ?: "—",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    // 0% 时连槽都不画：那条 6dp 的灰线在页面上会被读成一条分隔线
    if (pct != null && pct > 0) {
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((pct.coerceIn(2, 100)) / 100f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}
