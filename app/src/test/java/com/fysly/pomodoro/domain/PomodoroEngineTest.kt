package com.fysly.pomodoro.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PomodoroEngineTest {

    private val engine = PomodoroEngine()

    private val config = TimerConfig(
        focusMinutes = 25,
        shortBreakMinutes = 5,
        longBreakMinutes = 15,
        cyclesBeforeLongBreak = 4,
        autoStartBreaks = false,
        autoStartFocus = false,
    )

    private val t0 = 1_000_000L

    @Test
    fun `初始状态停在专注起点且未运行`() {
        val state = engine.initialState(config)
        assertEquals(PomodoroPhase.FOCUS, state.phase)
        assertEquals(25 * 60, state.remainingSeconds)
        assertEquals(25 * 60, state.totalSeconds)
        assertFalse(state.isRunning)
    }

    @Test
    fun `开始后剩余时间随流逝的秒数减少`() {
        val started = engine.start(engine.initialState(config), t0)
        assertTrue(started.isRunning)

        val after10s = engine.advance(started, config, t0 + 10_000).state
        assertEquals(25 * 60 - 10, after10s.remainingSeconds)
        assertTrue(after10s.isRunning)
    }

    @Test
    fun `不足一秒的推进不会消耗剩余时间`() {
        val started = engine.start(engine.initialState(config), t0)
        // 300ms 后剩余时间应该仍然按整秒向上取整，不能提前跳秒
        val state = engine.advance(started, config, t0 + 300).state
        assertEquals(25 * 60, state.remainingSeconds)
    }

    @Test
    fun `暂停后时间冻结并保留剩余秒数`() {
        val started = engine.start(engine.initialState(config), t0)
        val paused = engine.pause(started, config, t0 + 60_000)

        assertFalse(paused.isRunning)
        assertEquals(25 * 60 - 60, paused.remainingSeconds)

        // 暂停期间再怎么推进也不变
        val stillPaused = engine.advance(paused, config, t0 + 600_000).state
        assertEquals(25 * 60 - 60, stillPaused.remainingSeconds)
    }

    @Test
    fun `继续计时会从暂停处接着走`() {
        val started = engine.start(engine.initialState(config), t0)
        val paused = engine.pause(started, config, t0 + 60_000)
        val resumed = engine.start(paused, t0 + 600_000)

        val state = engine.advance(resumed, config, t0 + 600_000 + 5_000).state
        assertEquals(25 * 60 - 60 - 5, state.remainingSeconds)
    }

    @Test
    fun `专注走完进入短休息且计数加一`() {
        val started = engine.start(engine.initialState(config), t0)
        val result = engine.advance(started, config, t0 + 25 * 60_000L)

        assertEquals(1, result.completed.size)
        assertEquals(PomodoroPhase.FOCUS, result.completed.first().phase)
        assertEquals(PomodoroPhase.SHORT_BREAK, result.state.phase)
        assertEquals(1, result.state.completedFocusInSet)
        // 未开启自动开始，应停下等待用户
        assertFalse(result.state.isRunning)
        assertEquals(5 * 60, result.state.remainingSeconds)
    }

    @Test
    fun `第四个专注结束后进入长休息并把计数归零留待长休息后`() {
        var state = engine.initialState(config)
        val completedPhases = mutableListOf<PomodoroPhase>()

        // 走完 4 个完整循环：专注 -> 短休息 -> ... -> 第 4 个专注 -> 长休息
        repeat(4) {
            state = engine.start(state, t0)
            val focus = engine.advance(state, config, t0 + state.remainingSeconds * 1000L)
            completedPhases += focus.completed.map { it.phase }
            state = focus.state

            if (state.phase == PomodoroPhase.LONG_BREAK) return@repeat

            state = engine.start(state, t0)
            val rest = engine.advance(state, config, t0 + state.remainingSeconds * 1000L)
            completedPhases += rest.completed.map { it.phase }
            state = rest.state
        }

        assertEquals(PomodoroPhase.LONG_BREAK, state.phase)
        assertEquals(4, state.completedFocusInSet)
        assertEquals(
            listOf(
                PomodoroPhase.FOCUS, PomodoroPhase.SHORT_BREAK,
                PomodoroPhase.FOCUS, PomodoroPhase.SHORT_BREAK,
                PomodoroPhase.FOCUS, PomodoroPhase.SHORT_BREAK,
                PomodoroPhase.FOCUS,
            ),
            completedPhases,
        )
    }

    @Test
    fun `长休息结束后计数清零回到专注`() {
        var state = engine.initialState(config)
        repeat(4) {
            state = engine.start(state, t0)
            state = engine.advance(state, config, t0 + state.remainingSeconds * 1000L).state
            if (state.phase == PomodoroPhase.LONG_BREAK) return@repeat
            state = engine.start(state, t0)
            state = engine.advance(state, config, t0 + state.remainingSeconds * 1000L).state
        }

        assertEquals(PomodoroPhase.LONG_BREAK, state.phase)

        state = engine.start(state, t0)
        val afterLongBreak = engine.advance(state, config, t0 + 15 * 60_000L)
        assertEquals(PomodoroPhase.FOCUS, afterLongBreak.state.phase)
        assertEquals(0, afterLongBreak.state.completedFocusInSet)
    }

    @Test
    fun `开启自动开始后阶段会自己衔接`() {
        val auto = config.copy(autoStartBreaks = true, autoStartFocus = true)
        val started = engine.start(engine.initialState(auto), t0)

        val result = engine.advance(started, auto, t0 + 25 * 60_000L)
        assertEquals(PomodoroPhase.SHORT_BREAK, result.state.phase)
        assertTrue(result.state.isRunning)
    }

    @Test
    fun `后台停留很久会一次补齐多个阶段`() {
        val auto = config.copy(autoStartBreaks = true, autoStartFocus = true)
        val started = engine.start(engine.initialState(auto), t0)

        // 离开 25 + 5 + 25 = 55 分钟，应补齐专注、短休息、专注三个阶段
        val result = engine.advance(started, auto, t0 + 55 * 60_000L)

        assertEquals(
            listOf(PomodoroPhase.FOCUS, PomodoroPhase.SHORT_BREAK, PomodoroPhase.FOCUS),
            result.completed.map { it.phase },
        )
        assertEquals(PomodoroPhase.SHORT_BREAK, result.state.phase)
        assertEquals(2, result.state.completedFocusInSet)
        assertTrue(result.state.isRunning)
    }

    @Test
    fun `完成时刻按真实截止时间记录而不是发现时间`() {
        val started = engine.start(engine.initialState(config), t0)
        val result = engine.advance(started, config, t0 + 30 * 60_000L)

        // 实际在 t0 + 25min 结束，即便 30min 后才发现
        assertEquals(t0 + 25 * 60_000L, result.completed.first().completedAtElapsedRealtime)
    }

    @Test
    fun `重置保留已完成计数但回到阶段起点`() {
        var state = engine.start(engine.initialState(config), t0)
        state = engine.advance(state, config, t0 + 25 * 60_000L).state
        assertEquals(1, state.completedFocusInSet)

        val reset = engine.reset(state, config)
        assertEquals(PomodoroPhase.SHORT_BREAK, reset.phase)
        assertEquals(5 * 60, reset.remainingSeconds)
        assertFalse(reset.isRunning)
        assertEquals(1, reset.completedFocusInSet)
    }

    @Test
    fun `重来一组回到最初的专注`() {
        var state = engine.start(engine.initialState(config), t0)
        state = engine.advance(state, config, t0 + 25 * 60_000L).state

        val fresh = engine.resetAll(config)
        assertEquals(PomodoroPhase.FOCUS, fresh.phase)
        assertEquals(0, fresh.completedFocusInSet)
        assertEquals(25 * 60, fresh.remainingSeconds)
        assertFalse(fresh.isRunning)
    }

    @Test
    fun `跳过专注不算完成也不推进组内计数`() {
        val started = engine.start(engine.initialState(config), t0)
        val skipped = engine.skip(started, config, t0 + 60_000)

        assertEquals(PomodoroPhase.SHORT_BREAK, skipped.phase)
        // 关键：连点跳过不应该白拿一个长休息
        assertEquals(0, skipped.completedFocusInSet)
        assertEquals(5 * 60, skipped.remainingSeconds)
    }

    @Test
    fun `跳过时保持原来的运行状态`() {
        val running = engine.start(engine.initialState(config), t0)
        val skippedWhileRunning = engine.skip(running, config, t0 + 60_000)
        assertTrue(skippedWhileRunning.isRunning)

        val paused = engine.initialState(config)
        val skippedWhilePaused = engine.skip(paused, config, t0)
        assertFalse(skippedWhilePaused.isRunning)
    }

    @Test
    fun `跳过长休息会清零组内计数`() {
        // 先攒满一组，进入长休息
        var state = engine.initialState(config)
        repeat(4) {
            state = engine.start(state, t0)
            state = engine.advance(state, config, t0 + state.remainingSeconds * 1000L).state
            if (state.phase == PomodoroPhase.LONG_BREAK) return@repeat
            state = engine.start(state, t0)
            state = engine.advance(state, config, t0 + state.remainingSeconds * 1000L).state
        }
        assertEquals(PomodoroPhase.LONG_BREAK, state.phase)
        assertEquals(4, state.completedFocusInSet)

        val skipped = engine.skip(state, config, t0)
        assertEquals(PomodoroPhase.FOCUS, skipped.phase)
        assertEquals(0, skipped.completedFocusInSet)
    }

    @Test
    fun `连续跳过不会推进组内计数`() {
        var state = engine.initialState(config)
        repeat(10) {
            state = engine.skip(state, config, t0)
        }
        assertEquals(0, state.completedFocusInSet)
    }

    @Test
    fun `改设置不会打断正在运行的阶段`() {
        val started = engine.start(engine.initialState(config), t0)
        val changed = config.copy(focusMinutes = 50)

        val state = engine.applyConfig(started, changed)
        assertEquals(25 * 60, state.totalSeconds)
        assertTrue(state.isRunning)
    }

    @Test
    fun `改设置会同步到未运行的阶段`() {
        val idle = engine.initialState(config)
        val state = engine.applyConfig(idle, config.copy(focusMinutes = 50))

        assertEquals(50 * 60, state.remainingSeconds)
        assertEquals(50 * 60, state.totalSeconds)
    }

    @Test
    fun `非法配置会被夹到安全区间`() {
        val bad = TimerConfig(
            focusMinutes = 0,
            shortBreakMinutes = -5,
            longBreakMinutes = 9999,
            cyclesBeforeLongBreak = 0,
        ).sanitized()

        assertEquals(TimerConfig.MIN_MINUTES, bad.focusMinutes)
        assertEquals(TimerConfig.MIN_MINUTES, bad.shortBreakMinutes)
        assertEquals(TimerConfig.MAX_MINUTES, bad.longBreakMinutes)
        assertEquals(TimerConfig.MIN_CYCLES, bad.cyclesBeforeLongBreak)
    }

    @Test
    fun `单次循环的配置不会卡死也不会无限追赶`() {
        // cycles = 1 表示每个专注后都长休息，历史实现里容易写成死循环
        val single = config.copy(cyclesBeforeLongBreak = 1)
        val started = engine.start(engine.initialState(single), t0)
        val result = engine.advance(started, single, t0 + 25 * 60_000L)

        assertEquals(PomodoroPhase.LONG_BREAK, result.state.phase)
        assertEquals(1, result.state.completedFocusInSet)
    }

    @Test
    fun `进度按已过去比例计算`() {
        val started = engine.start(engine.initialState(config), t0)
        val quarter = engine.advance(started, config, t0 + 25 * 60_000L / 4).state

        assertEquals(0.25f, quarter.progress, 0.01f)
    }

    // ---------- 持久化恢复 ----------

    @Test
    fun `正常的快照能接着走`() {
        // 10 分钟前开始，运行中，已经过去 10 分钟
        val saved = TimerState(
            phase = PomodoroPhase.FOCUS,
            remainingSeconds = 15 * 60,
            totalSeconds = 25 * 60,
            isRunning = true,
            completedFocusInSet = 1,
            deadlineElapsedRealtime = t0 + 15 * 60_000L,
        )

        val restored = engine.restore(saved, config, t0)

        assertEquals(PomodoroPhase.FOCUS, restored.phase)
        assertTrue(restored.isRunning)
        assertEquals(15 * 60, restored.remainingSeconds)
        assertEquals(1, restored.completedFocusInSet)
    }

    @Test
    fun `跨重启的脏快照会被丢弃`() {
        // 重启前系统已开机很久，存下的截止时刻是个很大的值；
        // 重启后 elapsedRealtime 归零，这个截止时刻就成了三十多个小时之后。
        val beforeRebootDeadline = 116_000_000L
        val saved = TimerState(
            phase = PomodoroPhase.FOCUS,
            remainingSeconds = 20 * 60,
            totalSeconds = 25 * 60,
            isRunning = true,
            completedFocusInSet = 2,
            deadlineElapsedRealtime = beforeRebootDeadline,
        )

        // 重启后刚过 60 秒
        val restored = engine.restore(saved, config, 60_000L)

        assertEquals(PomodoroPhase.FOCUS, restored.phase)
        assertFalse(restored.isRunning)
        assertEquals(25 * 60, restored.remainingSeconds)
        assertEquals(0, restored.completedFocusInSet)
    }

    @Test
    fun `剩余时间超过总时长的损坏快照会被丢弃`() {
        val broken = TimerState(
            phase = PomodoroPhase.FOCUS,
            remainingSeconds = 99 * 60,
            totalSeconds = 25 * 60,
            isRunning = false,
            deadlineElapsedRealtime = 0L,
        )

        val restored = engine.restore(broken, config, t0)

        assertEquals(25 * 60, restored.remainingSeconds)
        assertEquals(0, restored.completedFocusInSet)
    }

    @Test
    fun `总时长不合理的快照会被丢弃`() {
        val broken = TimerState(
            phase = PomodoroPhase.FOCUS,
            remainingSeconds = 0,
            totalSeconds = 0,
            isRunning = true,
            deadlineElapsedRealtime = t0,
        )

        val restored = engine.restore(broken, config, t0)

        assertEquals(25 * 60, restored.totalSeconds)
        assertFalse(restored.isRunning)
    }

    @Test
    fun `暂停中的快照恢复后仍然是暂停且时间不变`() {
        val saved = TimerState(
            phase = PomodoroPhase.SHORT_BREAK,
            remainingSeconds = 3 * 60,
            totalSeconds = 5 * 60,
            isRunning = false,
            completedFocusInSet = 1,
            deadlineElapsedRealtime = 0L,
        )

        // 就算中间隔了很久，暂停的快照也不该自己往前走
        val restored = engine.restore(saved, config, t0 + 9_000_000L)

        assertEquals(PomodoroPhase.SHORT_BREAK, restored.phase)
        assertFalse(restored.isRunning)
        assertEquals(3 * 60, restored.remainingSeconds)
        assertEquals(1, restored.completedFocusInSet)
    }

    @Test
    fun `超过一周没打开的快照不会无限追赶`() {
        val saved = TimerState(
            phase = PomodoroPhase.FOCUS,
            remainingSeconds = 60,
            totalSeconds = 25 * 60,
            isRunning = true,
            completedFocusInSet = 0,
            // 截止时刻早于当前时间整整一星期
            deadlineElapsedRealtime = t0 - 7 * 24 * 3600_000L,
        )

        val auto = config.copy(autoStartBreaks = true, autoStartFocus = true)
        val restored = engine.restore(saved, auto, t0)

        // 不该抛异常，也不该把七天的阶段全部补出来
        assertTrue(restored.totalSeconds > 0)
        assertEquals(restored.remainingSeconds, restored.remainingSeconds.coerceAtLeast(0))
    }
}
