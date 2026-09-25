package com.fysly.pomodoro.timer

import androidx.annotation.ColorInt
import com.fysly.pomodoro.data.ThemeColor
import com.fysly.pomodoro.domain.PomodoroPhase

/**
 * 通知与实时活动里用的强调色。
 *
 * 取值与 `ui/theme/Color.kt` 中的主题色样本保持一致，但那边是 Compose 的 `Color`，
 * 而 `NotificationCompat.ProgressStyle.Segment` 需要的是普通 ARGB int，
 * 通知层也不应该反向依赖 UI 层，所以这里单独列一份。改主题色时两边都要改。
 */
object NotificationAccent {

    @ColorInt
    fun of(phase: PomodoroPhase, themeColor: ThemeColor): Int = when (phase) {
        PomodoroPhase.FOCUS -> focus(themeColor)
        PomodoroPhase.SHORT_BREAK -> SHORT_BREAK
        PomodoroPhase.LONG_BREAK -> LONG_BREAK
    }

    @ColorInt
    private fun focus(themeColor: ThemeColor): Int = when (themeColor) {
        ThemeColor.TOMATO -> 0xFFD84315.toInt()
        ThemeColor.FOREST -> 0xFF2E7D32.toInt()
        ThemeColor.OCEAN -> 0xFF0277BD.toInt()
        ThemeColor.GRAPE -> 0xFF7B4E9B.toInt()
        ThemeColor.AMBER -> 0xFFF57F17.toInt()
        ThemeColor.SLATE -> 0xFF455A64.toInt()
    }

    /** 休息阶段用固定色，一眼就能和专注区分开，不跟随主题色。 */
    @ColorInt
    private const val SHORT_BREAK = 0xFF1F8A5B.toInt()

    @ColorInt
    private const val LONG_BREAK = 0xFF2F6FB0.toInt()
}
