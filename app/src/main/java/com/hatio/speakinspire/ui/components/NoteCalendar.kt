package com.hatio.speakinspire.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatio.speakinspire.domain.DayLog
import com.hatio.speakinspire.domain.MonthGrid
import com.hatio.speakinspire.ui.theme.Spacing
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月份日历。统计页的门面。
 *
 * ## 为什么它值得占掉第一屏三分之一
 *
 * 统计页原来第一眼是三个大数字。「我记了 42 条」是个存量，
 * 看不出节奏——记了 40 天每天一条，和某三天各记了十几条，
 * 数字一模一样，但那完全是两种状态。
 *
 * 日历把这层信息一次画出来：哪几天有，中间断在哪，一眼就是。
 * 所以它放在最上面，而且不做折叠——不做主角的东西没有资格放第一屏。
 *
 * ## 只有四种深浅
 *
 * 0 / 1 / 2 / 3+。记 8 条和记 3 条在「我今天写了没有」这件事上
 * 没有区别，再细分只会让人盯着颜色跟自己比。
 *
 * ## 点击只回答「那天记了什么」
 *
 * 不跳转到筛选后的列表——那要引入一个新路由和一个新的空状态，
 * 而用户点一下想看的多半只是「哦，那天我在想这个」。
 */
@Composable
fun NoteCalendar(
    month: YearMonth,
    log: DayLog,
    today: LocalDate,
    selected: LocalDate?,
    onSelectMonth: (YearMonth) -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thisMonth = YearMonth.from(today)
    val cells = MonthGrid.cells(month)

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelectMonth(month.minusMonths(1)) }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "上个月",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${month.year} 年 ${month.monthValue} 月",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(
                onClick = { onSelectMonth(month.plusMonths(1)) },
                // 未来还没发生，翻过去只会看到一片空白
                enabled = month < thisMonth,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "下个月",
                    tint = if (month < thisMonth) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            if (month != thisMonth) {
                TextButton(onClick = { onSelectMonth(thisMonth) }) {
                    Text("回到本月", fontSize = 12.sp)
                }
            }
        }

        Text(
            text = monthSummary(month, log),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.s),
        )

        Row(Modifier.fillMaxWidth()) {
            MonthGrid.weekdayLabels.forEach { label ->
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(Spacing.xs))

        // 自己按 7 个一行切行，不用 LazyVerticalGrid：
        // 日历在一个可滚动页面里，嵌套滚动会让手势打架
        cells.chunked(MonthGrid.COLUMNS).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        DayCell(
                            date = date,
                            level = log.levelOf(date),
                            isToday = date == today,
                            isSelected = date == selected,
                            future = date.isAfter(today),
                            onClick = { onSelectDay(date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (selected != null && YearMonth.from(selected) == month) {
            Spacer(Modifier.height(Spacing.s))
            SelectedDayLine(date = selected, log = log)
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    level: Int,
    isToday: Boolean,
    isSelected: Boolean,
    future: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    // 四档：0 / 1 / 2 / 3+。档位越高越实，但都不深——
    // 这是一张月度分布图，不是警告
    val fill = when (level) {
        0 -> androidx.compose.ui.graphics.Color.Transparent
        1 -> primary.copy(alpha = 0.12f)
        2 -> primary.copy(alpha = 0.26f)
        else -> primary.copy(alpha = 0.42f)
    }
    val ring = when {
        isSelected -> primary
        isToday -> MaterialTheme.colorScheme.outline
        else -> androidx.compose.ui.graphics.Color.Transparent
    }

    Box(
        modifier = modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = ring,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(enabled = !future, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            fontSize = 12.sp,
            fontWeight = if (level > 0) FontWeight.Medium else FontWeight.Normal,
            color = when {
                future -> MaterialTheme.colorScheme.outlineVariant
                level > 0 -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun SelectedDayLine(date: LocalDate, log: DayLog) {
    val count = log.countOf(date)
    val label = "${date.monthValue} 月 ${date.dayOfMonth} 日"
    Text(
        text = if (count == 0) "$label · 那天没有记录" else "$label · 记了 $count 条",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (count > 0) {
        val first = log.firstLineOf(date)
        if (first.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = first,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun monthSummary(month: YearMonth, log: DayLog): String {
    val total = log.totalIn(month)
    val days = log.daysIn(month)
    return if (total == 0) {
        "这个月还没记"
    } else {
        "记了 $total 条 · 有 $days 天"
    }
}

/** 三数字那一行。日历是主角，所以这些数字要让位 */
@Composable
fun StatNumber(value: Int, label: String, color: androidx.compose.ui.graphics.Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = Spacing.s),
    ) {
        Text(
            text = value.toString(),
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 统计页里三条链的小卡片 */
@Composable
fun StatCard(
    title: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 12.dp)
                    .background(accent, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        content()
    }
}
