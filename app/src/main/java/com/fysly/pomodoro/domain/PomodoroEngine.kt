package com.fysly.pomodoro.domain

/**
 * 番茄钟状态机。纯函数实现，不依赖任何 Android API，因此可以直接单元测试。
 *
 * 约定：所有 [now] 参数都是 `SystemClock.elapsedRealtime()` 的毫秒值（单调递增，不受改系统时间影响）。
 */
class PomodoroEngine {

    /** 全新一轮的初始状态：停在专注阶段起点，等待用户按下开始。 */
    fun initialState(config: TimerConfig): TimerState {
        val cfg = config.sanitized()
        val duration = cfg.durationSeconds(PomodoroPhase.FOCUS)
        return TimerState(
            phase = PomodoroPhase.FOCUS,
            remainingSeconds = duration,
            totalSeconds = duration,
            isRunning = false,
            completedFocusInSet = 0,
            deadlineElapsedRealtime = 0L,
        )
    }

    /**
     * 从持久化快照恢复。
     *
     * 这里必须校验，原因是 [TimerState.deadlineElapsedRealtime] 用的是
     * `SystemClock.elapsedRealtime()`——**开机以来的毫秒数，重启后会归零**。
     * 重启前存下的截止时刻在重启后可能比当前时间大出几十个小时，
     * 直接拿来算剩余时间，界面上就会显示 32:16:50 这种离谱的数字。
     *
     * 判断依据：剩余时间不可能超过所在阶段的总时长。超过就说明这份快照
     * 跨越了重启（或者数据损坏），丢弃它、重新开始一轮。
     */
    fun restore(saved: TimerState, config: TimerConfig, now: Long): TimerState {
        val cfg = config.sanitized()

        val structurallySane = saved.totalSeconds > 0 &&
            saved.remainingSeconds in 0..saved.totalSeconds
        if (!structurallySane) return initialState(cfg)

        if (saved.isRunning) {
            val remainingMillis = saved.deadlineElapsedRealtime - now
            val impossible = remainingMillis > saved.totalSeconds * 1000L
            if (impossible) return initialState(cfg)
        }

        return advance(saved, cfg, now).state
    }

    /** 开始（或从暂停处继续）。 */
    fun start(state: TimerState, now: Long): TimerState {
        if (state.isRunning) return state
        val remaining = state.remainingSeconds.coerceAtLeast(0)
        if (remaining == 0) return state
        return state.copy(
            isRunning = true,
            deadlineElapsedRealtime = now + remaining * 1000L,
        )
    }

    /** 暂停，先把已经流逝的时间结算掉。 */
    fun pause(state: TimerState, config: TimerConfig, now: Long): TimerState {
        if (!state.isRunning) return state
        val settled = advance(state, config, now).state
        return settled.copy(isRunning = false, deadlineElapsedRealtime = 0L)
    }

    /** 重置当前阶段：回到本阶段起点并停下，不清空已完成计数。 */
    fun reset(state: TimerState, config: TimerConfig): TimerState {
        val cfg = config.sanitized()
        val duration = cfg.durationSeconds(state.phase)
        return state.copy(
            remainingSeconds = duration,
            totalSeconds = duration,
            isRunning = false,
            deadlineElapsedRealtime = 0L,
        )
    }

    /**
     * 重置整组进度：回到第一个专注，已完成计数清零。
     * 界面上的"长按重置"用这个。
     */
    fun resetAll(config: TimerConfig): TimerState = initialState(config)

    /**
     * 跳过当前阶段，直接进入下一个。
     *
     * 两个刻意的设计：
     * - 跳过的阶段不计入统计，也不推进组内计数——屏幕上的圆点代表真正做完的专注，
     *   否则用户连点几次跳过就能白拿一个长休息。
     * - 计时状态保持不变：本来在跑就继续跑下一阶段，本来是暂停就停在下一阶段起点。
     */
    fun skip(state: TimerState, config: TimerConfig, now: Long): TimerState {
        val cfg = config.sanitized()
        val settled = if (state.isRunning) advance(state, cfg, now).state else state

        val nextPhase = when (settled.phase) {
            PomodoroPhase.FOCUS -> PomodoroPhase.SHORT_BREAK
            PomodoroPhase.SHORT_BREAK, PomodoroPhase.LONG_BREAK -> PomodoroPhase.FOCUS
        }
        // 离开长休息时清零，与自然结束的规则保持一致
        val nextCount = if (settled.phase == PomodoroPhase.LONG_BREAK) {
            0
        } else {
            settled.completedFocusInSet
        }

        val duration = cfg.durationSeconds(nextPhase)
        val keepRunning = settled.isRunning

        return TimerState(
            phase = nextPhase,
            remainingSeconds = duration,
            totalSeconds = duration,
            isRunning = keepRunning,
            completedFocusInSet = nextCount,
            deadlineElapsedRealtime = if (keepRunning) now + duration * 1000L else 0L,
        )
    }

    /**
     * 把时间推进到 [now]。若运行期间跨越了一个或多个阶段结束点，会依次补齐，
     * 并在 [AdvanceResult.completed] 里返回全部走完的阶段（后台待久了可能不止一个）。
     */
    fun advance(state: TimerState, config: TimerConfig, now: Long): AdvanceResult {
        if (!state.isRunning) return AdvanceResult(state)

        val cfg = config.sanitized()
        val completed = mutableListOf<CompletedPhase>()
        var current = state
        var deadline = state.deadlineElapsedRealtime

        // 正常情况下这个循环最多跑一轮；后台停留很久时才会连续补齐。
        // 设上限是为了防御异常的配置（比如时长为 0）导致死循环。
        var guard = 0
        while (guard++ < MAX_CATCH_UP_STEPS) {
            if (now < deadline) {
                val remaining = ((deadline - now) + 999L) / 1000L
                return AdvanceResult(
                    state = current.copy(
                        remainingSeconds = remaining.toInt(),
                        isRunning = true,
                        deadlineElapsedRealtime = deadline,
                    ),
                    completed = completed,
                )
            }

            // 当前阶段到点了
            completed += CompletedPhase(
                phase = current.phase,
                durationSeconds = current.totalSeconds,
                completedAtElapsedRealtime = deadline,
            )

            val (nextPhase, nextCount) = nextPhaseOf(current, cfg)
            val duration = cfg.durationSeconds(nextPhase)
            val autoStart = cfg.shouldAutoStart(nextPhase)

            current = current.copy(
                phase = nextPhase,
                remainingSeconds = duration,
                totalSeconds = duration,
                completedFocusInSet = nextCount,
            )

            if (!autoStart) {
                return AdvanceResult(
                    state = current.copy(isRunning = false, deadlineElapsedRealtime = 0L),
                    completed = completed,
                )
            }
            // 自动开始：以原截止时刻为基准继续累加，保持节奏不漂移
            deadline += duration * 1000L
            current = current.copy(isRunning = true, deadlineElapsedRealtime = deadline)
        }

        // 兜底：把剩余时间直接归零并停下，避免持续追赶
        return AdvanceResult(
            state = current.copy(
                remainingSeconds = 0,
                isRunning = false,
                deadlineElapsedRealtime = 0L,
            ),
            completed = completed,
        )
    }

    /**
     * 设置里改了时长之后同步到当前状态。只在计时未运行时生效，避免把正在跑的阶段打断。
     */
    fun applyConfig(state: TimerState, config: TimerConfig): TimerState {
        if (state.isRunning) return state
        val cfg = config.sanitized()
        val duration = cfg.durationSeconds(state.phase)
        if (state.totalSeconds == duration) return state
        return state.copy(remainingSeconds = duration, totalSeconds = duration)
    }

    /**
     * 决定下一个阶段，以及更新后的"本组已完成专注次数"。
     *
     * 专注结束：计数 +1；达到 [TimerConfig.cyclesBeforeLongBreak] 的整数倍则长休息，否则短休息。
     * 长休息结束：计数清零，回到专注。
     * 短休息结束：计数不变，回到专注。
     */
    private fun nextPhaseOf(state: TimerState, config: TimerConfig): Pair<PomodoroPhase, Int> =
        when (state.phase) {
            PomodoroPhase.FOCUS -> {
                val count = state.completedFocusInSet + 1
                val isLongBreakDue = count % config.cyclesBeforeLongBreak == 0
                val next = if (isLongBreakDue) PomodoroPhase.LONG_BREAK else PomodoroPhase.SHORT_BREAK
                next to count
            }

            PomodoroPhase.SHORT_BREAK -> PomodoroPhase.FOCUS to state.completedFocusInSet

            PomodoroPhase.LONG_BREAK -> PomodoroPhase.FOCUS to 0
        }

    private companion object {
        const val MAX_CATCH_UP_STEPS = 64
    }
}
