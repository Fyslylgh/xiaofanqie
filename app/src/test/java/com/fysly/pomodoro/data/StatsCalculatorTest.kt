package com.fysly.pomodoro.data

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsCalculatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun session(at: Long, seconds: Int = 1500, title: String? = null) =
        SessionRecord(endedAt = at, durationSeconds = seconds, taskTitle = title)

    private val now = at(2025, 1, 15, 12)

    @Test
    fun `没有记录时全部为零且补齐七天`() {
        val summary = StatsCalculator.summarize(emptyList(), now, zone, dailyGoal = 8)

        assertEquals(0, summary.todayCount)
        assertEquals(0, summary.totalCount)
        assertEquals(0, summary.streakDays)
        assertEquals(7, summary.daily.size)
        assertEquals(0, summary.daily.last().focusCount)
    }

    @Test
    fun `今日番茄与专注秒数正确汇总`() {
        val sessions = listOf(
            session(at(2025, 1, 15, 9)),
            session(at(2025, 1, 15, 10), seconds = 1200),
            session(at(2025, 1, 14, 22)),
        )

        val summary = StatsCalculator.summarize(sessions, now, zone)

        assertEquals(2, summary.todayCount)
        assertEquals(1500 + 1200, summary.todaySeconds)
        assertEquals(3, summary.totalCount)
        assertEquals(1500 + 1200 + 1500, summary.totalSeconds)
    }

    @Test
    fun `最近七天的最后一格是今天且缺口补零`() {
        val sessions = listOf(
            session(at(2025, 1, 15, 9)),
            session(at(2025, 1, 13, 9)),
        )

        val summary = StatsCalculator.summarize(sessions, now, zone, days = 7)

        assertEquals(7, summary.daily.size)
        assertEquals(1, summary.daily.last().focusCount) // 今天
        assertEquals(0, summary.daily[5].focusCount) // 昨天休息
        assertEquals(1, summary.daily[4].focusCount) // 前天
        assertEquals(0, summary.daily.first().focusCount)
    }

    @Test
    fun `连续达标天数从今天往回数`() {
        val sessions = listOf(
            session(at(2025, 1, 15, 9)),
            session(at(2025, 1, 15, 10)),
            session(at(2025, 1, 15, 11)),
            session(at(2025, 1, 14, 9)),
            session(at(2025, 1, 14, 10)),
            session(at(2025, 1, 13, 9)),
            session(at(2025, 1, 13, 10)),
        )

        val summary = StatsCalculator.summarize(sessions, now, zone, dailyGoal = 2)

        assertEquals(3, summary.streakDays)
    }

    @Test
    fun `今天还没达标不算断连`() {
        // 今天一个番茄都还没做，但昨天和前天都达标了
        val sessions = listOf(
            session(at(2025, 1, 14, 9)),
            session(at(2025, 1, 14, 10)),
            session(at(2025, 1, 13, 9)),
            session(at(2025, 1, 13, 10)),
        )

        val summary = StatsCalculator.summarize(sessions, now, zone, dailyGoal = 2)

        assertEquals(2, summary.streakDays)
    }

    @Test
    fun `昨天断了就只算到今天为止`() {
        val sessions = listOf(
            session(at(2025, 1, 15, 9)),
            // 昨天没有记录
            session(at(2025, 1, 13, 9)),
        )

        val summary = StatsCalculator.summarize(sessions, now, zone, dailyGoal = 1)

        assertEquals(1, summary.streakDays)
    }

    @Test
    fun `跨天按本地时区切分`() {
        // 北京时间 1 月 15 日 00:30 与 1 月 14 日 23:30，必须落在不同的天
        val sessions = listOf(
            session(at(2025, 1, 15, 0, 30)),
            session(at(2025, 1, 14, 23, 30)),
        )

        val summary = StatsCalculator.summarize(sessions, now, zone, dailyGoal = 1)

        assertEquals(1, summary.todayCount)
        assertEquals(2, summary.totalCount)
        assertEquals(2, summary.streakDays)
    }
}
