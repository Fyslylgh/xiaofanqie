package com.fysly.pomodoro.domain

/**
 * 用户可配置的计时参数。
 *
 * @param focusMinutes 单个专注时长（分钟）
 * @param shortBreakMinutes 短休息时长（分钟）
 * @param longBreakMinutes 长休息时长（分钟）
 * @param cyclesBeforeLongBreak 每完成几个专注后进入长休息
 * @param autoStartBreaks 阶段切换后自动开始休息
 * @param autoStartFocus 休息结束后自动开始下一个专注
 */
data class TimerConfig(
    val focusMinutes: Int = DEFAULT_FOCUS_MINUTES,
    val shortBreakMinutes: Int = DEFAULT_SHORT_BREAK_MINUTES,
    val longBreakMinutes: Int = DEFAULT_LONG_BREAK_MINUTES,
    val cyclesBeforeLongBreak: Int = DEFAULT_CYCLES,
    val autoStartBreaks: Boolean = true,
    val autoStartFocus: Boolean = false,
) {
    fun durationSeconds(phase: PomodoroPhase): Int = when (phase) {
        PomodoroPhase.FOCUS -> focusMinutes
        PomodoroPhase.SHORT_BREAK -> shortBreakMinutes
        PomodoroPhase.LONG_BREAK -> longBreakMinutes
    } * 60

    /** 进入 [next] 阶段时是否应自动开始计时。 */
    fun shouldAutoStart(next: PomodoroPhase): Boolean =
        if (next == PomodoroPhase.FOCUS) autoStartFocus else autoStartBreaks

    /** 把各项参数夹到合法区间，避免出现 0 分钟或负数时长的死循环。 */
    fun sanitized(): TimerConfig = copy(
        focusMinutes = focusMinutes.coerceIn(MIN_MINUTES, MAX_FOCUS_MINUTES),
        shortBreakMinutes = shortBreakMinutes.coerceIn(MIN_MINUTES, MAX_MINUTES),
        longBreakMinutes = longBreakMinutes.coerceIn(MIN_MINUTES, MAX_MINUTES),
        cyclesBeforeLongBreak = cyclesBeforeLongBreak.coerceIn(MIN_CYCLES, MAX_CYCLES),
    )

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_FOCUS_MINUTES = 180
        const val MAX_MINUTES = 60
        const val MIN_CYCLES = 1
        const val MAX_CYCLES = 12

        const val DEFAULT_FOCUS_MINUTES = 25
        const val DEFAULT_SHORT_BREAK_MINUTES = 5
        const val DEFAULT_LONG_BREAK_MINUTES = 15
        const val DEFAULT_CYCLES = 4

        val Default = TimerConfig()
    }
}
