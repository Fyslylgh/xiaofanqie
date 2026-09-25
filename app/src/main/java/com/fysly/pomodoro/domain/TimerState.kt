package com.fysly.pomodoro.domain

import kotlinx.serialization.Serializable

/**
 * 计时器的完整快照。
 *
 * 运行中只保存 [deadlineElapsedRealtime]（基于 `SystemClock.elapsedRealtime()` 的截止时刻），
 * 剩余秒数由它推算。这样即使应用被切到后台、进程被系统冻结，回到前台时也能立刻算出正确剩余时间，
 * 不会因为协程被挂起而"偷跑"或"漏跑"。
 */
@Serializable
data class TimerState(
    val phase: PomodoroPhase = PomodoroPhase.FOCUS,
    /** 当前阶段剩余秒数；运行中该值由 [deadlineElapsedRealtime] 推算得出。 */
    val remainingSeconds: Int = 0,
    /** 当前阶段的总秒数，用于画进度环。 */
    val totalSeconds: Int = 0,
    val isRunning: Boolean = false,
    /** 当前这一组里已经完成的专注次数，用于决定何时进入长休息。 */
    val completedFocusInSet: Int = 0,
    /** 运行中阶段的截止时刻（elapsedRealtime 毫秒）；未运行时为 0。 */
    val deadlineElapsedRealtime: Long = 0L,
) {
    /** 进度 0f..1f，已过去的比例。 */
    val progress: Float
        get() = if (totalSeconds <= 0) 0f else {
            ((totalSeconds - remainingSeconds).toFloat() / totalSeconds).coerceIn(0f, 1f)
        }

    val isFresh: Boolean get() = remainingSeconds == totalSeconds
}

/** 一个刚刚走完的阶段，交给上层记录统计、响铃、震动。 */
data class CompletedPhase(
    val phase: PomodoroPhase,
    val durationSeconds: Int,
    /** 完成时刻，同样是 elapsedRealtime 毫秒。 */
    val completedAtElapsedRealtime: Long,
)

/** [PomodoroEngine.advance] 的返回值。 */
data class AdvanceResult(
    val state: TimerState,
    /** 本次推进中走完的阶段；可能是多个（后台停留过久时一次补齐）。 */
    val completed: List<CompletedPhase> = emptyList(),
)
