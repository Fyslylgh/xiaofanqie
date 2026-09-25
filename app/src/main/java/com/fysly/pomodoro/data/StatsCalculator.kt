package com.fysly.pomodoro.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 把专注记录汇总成统计页需要的数据。纯函数，方便单测。 */
object StatsCalculator {

    /**
     * @param sessions 全部专注记录
     * @param now 当前墙上时间（epoch 毫秒）
     * @param zone 用于按天分组的时区
     * @param dailyGoal 每日目标番茄数，用来算连续达标天数
     * @param days 统计图展示最近多少天
     */
    fun summarize(
        sessions: List<SessionRecord>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        dailyGoal: Int = 8,
        days: Int = 7,
    ): StatsSummary {
        if (sessions.isEmpty()) {
            return StatsSummary(daily = recentDays(emptyMap(), now, zone, days))
        }

        val byDay: Map<LocalDate, List<SessionRecord>> = sessions.groupBy {
            Instant.ofEpochMilli(it.endedAt).atZone(zone).toLocalDate()
        }

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayRecords = byDay[today].orEmpty()

        val daily = recentDays(
            counts = byDay.mapValues { (_, records) ->
                records.size to records.sumOf { it.durationSeconds }
            },
            now = now,
            zone = zone,
            days = days,
        )

        return StatsSummary(
            todayCount = todayRecords.size,
            todaySeconds = todayRecords.sumOf { it.durationSeconds },
            totalCount = sessions.size,
            totalSeconds = sessions.sumOf { it.durationSeconds },
            streakDays = streak(byDay, today, dailyGoal),
            daily = daily,
        )
    }

    /** 最近 [days] 天的每日统计，按时间正序，缺口补 0。 */
    private fun recentDays(
        counts: Map<LocalDate, Pair<Int, Int>>,
        now: Long,
        zone: ZoneId,
        days: Int,
    ): List<DailyStat> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val (count, seconds) = counts[date] ?: (0 to 0)
            DailyStat(
                dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                focusCount = count,
                focusSeconds = seconds,
            )
        }
    }

    /**
     * 连续达标天数：从今天往回数，每天番茄数达到 [dailyGoal] 才算达标。
     * 今天还没达标不算断——从昨天开始数，这样白天看统计不会显示成 0。
     */
    private fun streak(
        byDay: Map<LocalDate, List<SessionRecord>>,
        today: LocalDate,
        dailyGoal: Int,
    ): Int {
        val goal = dailyGoal.coerceAtLeast(1)
        var cursor = if (byDay[today].orEmpty().size >= goal) today else today.minusDays(1)
        var count = 0
        while (count < MAX_STREAK_LOOKBACK) {
            val reached = byDay[cursor].orEmpty().size
            if (reached >= goal) {
                count++
                cursor = cursor.minusDays(1)
            } else {
                break
            }
        }
        return count
    }

    private const val MAX_STREAK_LOOKBACK = 3650
}
