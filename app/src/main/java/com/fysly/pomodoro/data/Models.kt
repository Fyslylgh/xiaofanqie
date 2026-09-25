package com.fysly.pomodoro.data

import kotlinx.serialization.Serializable

/** 一条待办任务。 */
@Serializable
data class TaskItem(
    val id: String,
    val title: String,
    /** 预计需要几个番茄 */
    val estimatedPomodoros: Int = 1,
    /** 实际已完成的番茄数 */
    val completedPomodoros: Int = 0,
    val isDone: Boolean = false,
    val createdAt: Long = 0L,
) {
    val progress: Float
        get() = if (estimatedPomodoros <= 0) 0f else {
            (completedPomodoros.toFloat() / estimatedPomodoros).coerceIn(0f, 1f)
        }
}

/** 一条已完成的专注记录，统计页的数据来源。 */
@Serializable
data class SessionRecord(
    /** 完成时刻的墙上时间（epoch 毫秒），用于按天分组 */
    val endedAt: Long,
    /** 实际专注秒数 */
    val durationSeconds: Int,
    /** 关联任务标题；没有关联任务时为 null */
    val taskTitle: String? = null,
)

/** 用户偏好设置。 */
@Serializable
data class AppSettings(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val cyclesBeforeLongBreak: Int = 4,
    val autoStartBreaks: Boolean = true,
    val autoStartFocus: Boolean = false,
    /** 阶段结束时响铃 */
    val soundEnabled: Boolean = true,
    /** 阶段结束时震动 */
    val vibrationEnabled: Boolean = true,
    /** 计时中保持屏幕常亮 */
    val keepScreenOn: Boolean = false,
    /** 深色模式：跟随系统 / 强制浅色 / 强制深色 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 主题色 */
    val themeColor: ThemeColor = ThemeColor.TOMATO,
    /** 每日目标番茄数，用于统计页的目标进度 */
    val dailyGoal: Int = 8,
    /**
     * 媒体卡片模式。
     *
     * 计时期间建立一个真实的媒体会话（配一条静音音轨），把倒计时伪装成"正在播放的媒体"，
     * 从而出现在锁屏播放器和各家系统的音乐卡片里——OPPO 的音乐流体云读的就是这个入口。
     *
     * 副作用：会和其他音乐应用争抢媒体控件；静音音轨也会持续占用音频管线。
     * 因为副作用不小，默认关闭，由用户自己决定要不要开。
     */
    val mediaCardEnabled: Boolean = false,
    /** 媒体卡片上的封面（系统通常会把它全屏铺开当背景） */
    val mediaArtwork: MediaArtwork = MediaArtwork.GRADIENT,
)

/**
 * 媒体卡片封面的来源。
 *
 * 之所以要这个选项：系统会把封面拉大铺满整张卡片当背景，直接放启动图标会显得很突兀。
 */
@Serializable
enum class MediaArtwork {
    /** 跟随阶段强调色的对角渐变，专注/休息各一色，永远不会难看 */
    GRADIENT,

    /** 应用启动图标 */
    APP_ICON,

    /** 用户自己选的图片，存在应用私有目录里 */
    CUSTOM,
}

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class ThemeColor { TOMATO, FOREST, OCEAN, GRAPE, AMBER, SLATE }

/** 统计页展示需要的汇总数据。 */
data class DailyStat(
    /** 当天 0 点的时间戳 */
    val dayStart: Long,
    val focusCount: Int,
    val focusSeconds: Int,
)

data class StatsSummary(
    val todayCount: Int = 0,
    val todaySeconds: Int = 0,
    val totalCount: Int = 0,
    val totalSeconds: Int = 0,
    /** 连续达成每日目标的天数 */
    val streakDays: Int = 0,
    val daily: List<DailyStat> = emptyList(),
)
