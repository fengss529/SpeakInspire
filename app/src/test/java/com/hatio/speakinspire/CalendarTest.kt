package com.hatio.speakinspire

import com.hatio.speakinspire.domain.DayLogBuilder
import com.hatio.speakinspire.domain.MonthGrid
import com.hatio.speakinspire.domain.NoteStamp
import com.hatio.speakinspire.domain.Streak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 月历网格。
 *
 * 这些断言写的是**具体的年月日**，不是「用另一套日期算法算一遍再比」——
 * 后者两边一起错的时候永远是绿的。
 */
class MonthGridTest {

    @Test
    fun `2026 年 10 月 1 号是周四`() {
        // 已知事实：2026-10-29 是周四，往前 28 天 → 10-01 也是周四。
        // 周一起始，前面要空周一周二周三三格
        val cells = MonthGrid.cells(YearMonth.of(2026, 10))
        assertEquals("10 月 1 日是周四，前面该空三格", 3, cells.takeWhile { it == null }.size)
        assertEquals(LocalDate.of(2026, 10, 1), cells[3])
        assertEquals(LocalDate.of(2026, 10, 31), cells[33])
    }

    @Test
    fun `尾部补白让最后一行凑满一周`() {
        val cells = MonthGrid.cells(YearMonth.of(2026, 10))
        assertEquals("3 格补白 + 31 天 = 34，补到 35", 35, cells.size)
        assertEquals(0, cells.size % MonthGrid.COLUMNS)
    }

    @Test
    fun `2026 年 2 月 1 号是周日`() {
        // 1 月 31 天：周四（1/1）往后 31 天 = 周日
        val cells = MonthGrid.cells(YearMonth.of(2026, 2))
        assertEquals("周日是最后一列，前面该空六格", 6, cells.takeWhile { it == null }.size)
        assertEquals(LocalDate.of(2026, 2, 1), cells[6])
    }

    @Test
    fun `闰年二月有 29 天`() {
        val cells = MonthGrid.cells(YearMonth.of(2024, 2))
        assertEquals(29, cells.count { it != null })
        assertEquals(0, cells.size % MonthGrid.COLUMNS)
    }

    @Test
    fun `补白只在两端，中间没有空洞`() {
        for (month in 1..12) {
            val cells = MonthGrid.cells(YearMonth.of(2026, month))
            val firstReal = cells.indexOfFirst { it != null }
            val lastReal = cells.indexOfLast { it != null }
            assertTrue(
                "第 $month 月中间出现了 null",
                cells.subList(firstReal, lastReal + 1).none { it == null },
            )
            assertEquals(0, cells.size % MonthGrid.COLUMNS)
        }
    }

    @Test
    fun `表头七列且以周一开头`() {
        assertEquals(7, MonthGrid.weekdayLabels.size)
        assertEquals("一", MonthGrid.weekdayLabels.first())
        assertEquals("日", MonthGrid.weekdayLabels.last())
    }
}

/** 按天聚合。日历只数记录，不掺文章和选题 */
class DayLogTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun stamp(year: Int, month: Int, day: Int, hour: Int = 12, title: String = "", body: String = "") =
        NoteStamp(at(year, month, day, hour), title, body)

    @Test
    fun `同一天记两条算两格`() {
        val log = DayLogBuilder.build(
            listOf(
                stamp(2026, 10, 28, 9, title = "早上"),
                stamp(2026, 10, 28, 21, title = "晚上"),
            ),
            zone,
        )
        assertEquals(2, log.countOf(LocalDate.of(2026, 10, 28)))
        assertEquals(2, log.levelOf(LocalDate.of(2026, 10, 28)))
    }

    @Test
    fun `三条以上封顶第三档`() {
        val log = DayLogBuilder.build(
            (1..8).map { stamp(2026, 10, 1, hour = it, title = "第 $it 条") },
            zone,
        )
        assertEquals(8, log.countOf(LocalDate.of(2026, 10, 1)))
        assertEquals("再多的条数也不再加深", 3, log.levelOf(LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `没记的那天是零档`() {
        val log = DayLogBuilder.build(listOf(stamp(2026, 10, 1)), zone)
        assertEquals(0, log.levelOf(LocalDate.of(2026, 10, 2)))
        assertEquals("", log.firstLineOf(LocalDate.of(2026, 10, 2)))
    }

    @Test
    fun `空库不炸`() {
        val log = DayLogBuilder.build(emptyList(), zone)
        assertEquals(0, log.countOf(LocalDate.of(2026, 10, 1)))
        assertTrue(log.byDay.isEmpty())
    }

    @Test
    fun `摘要优先取标题`() {
        val log = DayLogBuilder.build(
            listOf(stamp(2026, 10, 5, title = "标题在这儿", body = "正文第一行")),
            zone,
        )
        assertEquals("标题在这儿", log.firstLineOf(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `没有标题就取正文第一个非空行`() {
        val log = DayLogBuilder.build(
            listOf(stamp(2026, 10, 5, title = "  ", body = "\n\n  第一句有内容\n第二句")),
            zone,
        )
        assertEquals("第一句有内容", log.firstLineOf(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `摘要取当天最早那条`() {
        val log = DayLogBuilder.build(
            listOf(
                stamp(2026, 10, 5, hour = 22, title = "晚上写的"),
                stamp(2026, 10, 5, hour = 8, title = "早上写的"),
            ),
            zone,
        )
        assertEquals("早上写的", log.firstLineOf(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `跨月分开统计`() {
        val log = DayLogBuilder.build(
            listOf(
                stamp(2026, 9, 30, title = "九月"),
                stamp(2026, 10, 1, title = "十月一"),
                stamp(2026, 10, 1, hour = 20, title = "十月一又一条"),
            ),
            zone,
        )
        val oct = YearMonth.of(2026, 10)
        assertEquals(2, log.totalIn(oct))
        assertEquals("十月只有 1 号这一天", 1, log.daysIn(oct))
        assertEquals(1, log.totalIn(YearMonth.of(2026, 9)))
    }

    @Test
    fun `同一天不同小时不会被拆成两天`() {
        val log = DayLogBuilder.build(
            listOf(
                stamp(2026, 10, 28, hour = 0),
                stamp(2026, 10, 28, hour = 23),
            ),
            zone,
        )
        assertEquals(1, log.byDay.size)
        assertEquals(2, log.countOf(LocalDate.of(2026, 10, 28)))
    }
}

/** 最长连续。和「当前连续」是两件事：断了之后当前归零，最长不归零 */
class StreakLongestTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun day(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `没有记录就是零`() {
        assertEquals(0, Streak.longest(emptyList()))
    }

    @Test
    fun `连着三天`() {
        val stamps = listOf(day(2026, 10, 1), day(2026, 10, 2), day(2026, 10, 3))
        assertEquals(3, Streak.longest(stamps))
    }

    @Test
    fun `断过之后取最长的那一段`() {
        val stamps = listOf(
            day(2026, 10, 1), day(2026, 10, 2), // 2 天
            day(2026, 10, 5), day(2026, 10, 6), day(2026, 10, 7), day(2026, 10, 8), // 4 天
            day(2026, 10, 20), // 1 天
        )
        assertEquals(4, Streak.longest(stamps))
    }

    @Test
    fun `同一天记很多条只算一天`() {
        val stamps = listOf(
            day(2026, 10, 1), day(2026, 10, 1), day(2026, 10, 1),
        )
        assertEquals(1, Streak.longest(stamps))
    }

    @Test
    fun `乱序输入也按日期算`() {
        val stamps = listOf(day(2026, 10, 3), day(2026, 10, 1), day(2026, 10, 2))
        assertEquals(3, Streak.longest(stamps))
    }
}

/** 日历补白用的边界：两个月份都能吞下 null 而不误判 */
class MonthGridBoundaryTest {

    @Test
    fun `只有一个补白的月份也凑满整周`() {
        // 2026-12-01 是周二 → 前一格补白；31 天 → 1+31=32，补到 35
        val cells = MonthGrid.cells(YearMonth.of(2026, 12))
        assertEquals(1, cells.takeWhile { it == null }.size)
        assertEquals(35, cells.size)
        assertNull(cells.last())
    }
}
