package com.hatio.speakinspire.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 月历网格。纯函数，便于测试。
 *
 * ## 为什么返回 `List<LocalDate?>` 而不是「周 × 日」二维表
 *
 * 界面是 `LazyVerticalGrid(columns = 7)`，它吃的就是一维列表。
 * 返回二维表再摊平，多一层转换只会多一处能算错的地方。
 * `null` 表示这一格是补白（月初之前 / 月末之后）。
 *
 * ## 为什么要补尾部的 null
 *
 * 不补的话，最后一行可能只有 2 格，`LazyVerticalGrid` 会把它们
 * 拉成两格宽——日历的列会突然不对齐。补满整周列宽才稳定。
 */
object MonthGrid {

    const val COLUMNS = 7

    /** 周一起始。中文语境里日历第一列是周一，不是周日 */
    private val WEEK_START = DayOfWeek.MONDAY

    fun cells(month: YearMonth): List<LocalDate?> {
        val first = month.atDay(1)
        // DayOfWeek 里 MONDAY.value = 1 … SUNDAY.value = 7，
        // 减 1 正好是「这个月 1 号前面空几格」
        val leading = first.dayOfWeek.value - WEEK_START.value
        val days = month.lengthOfMonth()
        val trailing = (COLUMNS - (leading + days) % COLUMNS) % COLUMNS

        return buildList(leading + days + trailing) {
            repeat(leading) { add(null) }
            for (day in 1..days) add(month.atDay(day))
            repeat(trailing) { add(null) }
        }
    }

    /** 表头。顺序必须和 [cells] 的第一列对齐，否则整张表会错位一格 */
    val weekdayLabels: List<String> = listOf("一", "二", "三", "四", "五", "六", "日")
}

/** 一天记了几条。日历只需要这个量级，不需要逐条展开 */
data class DayMark(
    val date: LocalDate,
    val count: Int,
    /** 那天最早那条的 title（没有就取正文第一行）。点某天时显示 */
    val firstLine: String,
)

/**
 * 按天聚合的记录分布。
 *
 * ## 为什么只数「记录」
 *
 * 这张日历要回答的是「我有没有在记」。混进文章和选题之后，
 * 同一格会出现两三种深浅不同的点，读的人得先分辨颜色含义，
 * 而它本来只是一眼扫过去的东西。
 *
 * 也**不数追问和发芽**：那些是加工，不是「今天留下了什么」。
 */
data class DayLog(val byDay: Map<LocalDate, DayMark> = emptyMap()) {

    fun countOf(date: LocalDate): Int = byDay[date]?.count ?: 0

    fun firstLineOf(date: LocalDate): String = byDay[date]?.firstLine.orEmpty()

    fun has(date: LocalDate): Boolean = byDay.containsKey(date)

    /**
     * 本月一共记了几条。
     *
     * ⚠️ 是**条数**，不是天数——要 `values.sumOf { it.count }`，
     * 不能 `keys.count`。后者返回的是「有几天在记」，
     * 一天记三条时会把三条算成一条。这两个数在界面上都显示，
     * 混了不会报错，只会让「记了 42 条 · 有 30 天」自相矛盾。
     */
    fun totalIn(month: YearMonth): Int = byDay.entries
        .filter { YearMonth.from(it.key) == month }
        .sumOf { it.value.count }

    /** 本月有几天在记 */
    fun daysIn(month: YearMonth): Int = byDay.keys.count { YearMonth.from(it) == month }

    /**
     * 深浅四档：0 / 1 / 2 / 3+。
     *
     * 不做逐条热力值——记 8 条和记 3 条在「我今天有没有写」这件事上
     * 没有区别，分得更细只会让人盯着颜色比较自己。
     */
    fun levelOf(date: LocalDate): Int = when (countOf(date)) {
        0 -> 0
        1 -> 1
        2 -> 2
        else -> 3
    }
}

/** 聚合需要的输入。只取这三样，避免把整个 NoteView 拖进领域层 */
data class NoteStamp(
    val createdAt: Long,
    val title: String,
    val body: String,
)

object DayLogBuilder {

    fun build(stamps: List<NoteStamp>, zone: ZoneId = ZoneId.systemDefault()): DayLog {
        if (stamps.isEmpty()) return DayLog()

        val byDay = stamps
            .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
            .mapValues { (_, sameDay) ->
                // 取当天最早那条当摘要：那是「这天一开始在想什么」，
                // 比最后一条更能代表这一天
                val first = sameDay.minByOrNull { it.createdAt }
                DayMark(
                    date = Instant.ofEpochMilli(first!!.createdAt).atZone(zone).toLocalDate(),
                    count = sameDay.size,
                    firstLine = first.firstLine(),
                )
            }

        return DayLog(byDay)
    }

    private fun NoteStamp.firstLine(): String {
        title.trim().takeIf { it.isNotEmpty() }?.let { return it }
        return body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }
}
