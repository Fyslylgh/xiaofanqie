package com.fysly.pomodoro.domain

import kotlinx.serialization.Serializable

/** 番茄钟的三个阶段。 */
@Serializable
enum class PomodoroPhase {
    /** 专注 */
    FOCUS,

    /** 短休息 */
    SHORT_BREAK,

    /** 长休息 */
    LONG_BREAK,
    ;

    val isBreak: Boolean get() = this != FOCUS
}

/** 阶段的中文名，用于界面与通知。 */
val PomodoroPhase.label: String
    get() = when (this) {
        PomodoroPhase.FOCUS -> "专注"
        PomodoroPhase.SHORT_BREAK -> "短休息"
        PomodoroPhase.LONG_BREAK -> "长休息"
    }
