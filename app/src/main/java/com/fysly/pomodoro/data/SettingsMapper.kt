package com.fysly.pomodoro.data

import com.fysly.pomodoro.domain.TimerConfig

/** 把持久化的用户设置映射成计时引擎要的参数。 */
fun AppSettings.toTimerConfig(): TimerConfig = TimerConfig(
    focusMinutes = focusMinutes,
    shortBreakMinutes = shortBreakMinutes,
    longBreakMinutes = longBreakMinutes,
    cyclesBeforeLongBreak = cyclesBeforeLongBreak,
    autoStartBreaks = autoStartBreaks,
    autoStartFocus = autoStartFocus,
).sanitized()
