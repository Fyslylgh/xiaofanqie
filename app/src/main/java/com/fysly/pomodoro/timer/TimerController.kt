package com.fysly.pomodoro.timer

import android.content.Context
import android.os.SystemClock
import com.fysly.pomodoro.data.AppSettings
import com.fysly.pomodoro.data.PomodoroRepository
import com.fysly.pomodoro.data.SessionRecord
import com.fysly.pomodoro.data.toTimerConfig
import com.fysly.pomodoro.domain.PomodoroEngine
import com.fysly.pomodoro.domain.PomodoroPhase
import com.fysly.pomodoro.domain.TimerConfig
import com.fysly.pomodoro.domain.TimerState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 计时过程中发生的事件，服务层据此响铃、震动、发通知。 */
sealed interface TimerEvent {
    /** 某个阶段自然走完（不是被跳过的）。 */
    data class PhaseCompleted(
        val completed: PomodoroPhase,
        val next: PomodoroPhase,
    ) : TimerEvent
}

/**
 * 计时器的唯一权威状态源，由 [com.fysly.pomodoro.PomodoroApplication] 持有，
 * 界面（ViewModel）和前台服务都订阅同一个实例，避免两套状态互相打架。
 *
 * 计时精度来自 [TimerState.deadlineElapsedRealtime]：tick 只负责"发现"阶段结束，
 * 不负责"累加"时间，所以协程被系统挂起也不会导致计时不准。
 */
class TimerController(
    private val context: Context,
    private val repository: PomodoroRepository,
    private val scope: CoroutineScope,
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val engine = PomodoroEngine()

    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _events = MutableSharedFlow<TimerEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TimerEvent> = _events.asSharedFlow()

    private var tickJob: Job? = null
    private val initialized = AtomicBoolean(false)

    /**
     * 最近一次尝试拉起前台服务是否成功。
     *
     * 快捷设置磁贴靠它判断要不要把界面带到前台兜底：Android 12+ 限制应用在后台启动
     * 前台服务，磁贴点击一般会被放行，但万一被拦下，计时会在没有前台服务的情况下裸跑，
     * 随时可能被系统回收。
     */
    var lastServiceStartSucceeded: Boolean = true
        private set

    private val config: TimerConfig get() = _settings.value.toTimerConfig()

    /** 应用启动时调用一次：恢复上次未走完的计时，并开始跟随设置变化。 */
    suspend fun initialize() {
        if (!initialized.compareAndSet(false, true)) return

        val loaded = repository.currentSettings()
        _settings.value = loaded
        val cfg = loaded.toTimerConfig()

        val saved = repository.timerState.first()
        val restored = saved
            // 走 restore 而不是直接 advance：它会把跨重启的脏快照挡掉，
            // 详见 PomodoroEngine.restore 的注释
            ?.let { engine.restore(it, cfg, elapsedClock()) }
            ?: engine.initialState(cfg)

        _state.value = restored

        if (restored.isRunning) {
            startTicking()
            TimerService.start(context)
        }

        // 设置变化时同步到当前状态（运行中的阶段不会被改动）
        scope.launch {
            repository.settings.collect { latest ->
                _settings.value = latest
                _state.value = engine.applyConfig(_state.value, latest.toTimerConfig())
            }
        }
    }

    // ---------- 用户操作 ----------

    fun start() {
        val current = _state.value
        if (current.isRunning) return
        _state.value = engine.start(current, elapsedClock())
        if (_state.value.isRunning) {
            startTicking()
            lastServiceStartSucceeded = TimerService.start(context)
            persistAsync()
        }
    }

    fun pause() {
        val current = _state.value
        if (!current.isRunning) return
        _state.value = engine.pause(current, config, elapsedClock())
        stopTicking()
        persistAsync()
    }

    fun toggle() {
        if (_state.value.isRunning) pause() else start()
    }

    /** 重置当前阶段。 */
    fun reset() {
        _state.value = engine.reset(_state.value, config)
        stopTicking()
        persistAsync()
    }

    /** 整组重来：回到第一个专注，已完成计数清零。 */
    fun resetAll() {
        _state.value = engine.resetAll(config)
        stopTicking()
        persistAsync()
    }

    /** 跳过当前阶段，进入下一个（不计入统计，也不推进组内计数）。 */
    fun skip() {
        scope.launch {
            // 先结算已经流逝的时间：万一阶段刚好在这个瞬间走完，不能把那次完成丢掉
            doTick()

            val now = elapsedClock()
            _state.value = engine.skip(_state.value, config, now)

            if (_state.value.isRunning) {
                startTicking()
                TimerService.start(context)
            } else {
                stopTicking()
            }
            persistAsync()
        }
    }

    /** 界面进入后台或即将被销毁时，把当前状态落盘。 */
    fun persistNow() {
        persistAsync()
    }

    // ---------- 内部 ----------

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                doTick()
                if (!_state.value.isRunning) break
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    private suspend fun doTick() {
        val now = elapsedClock()
        val previous = _state.value
        val result = engine.advance(previous, config, now)

        if (result.completed.isEmpty() && result.state == previous) return

        _state.value = result.state

        for (completed in result.completed) {
            recordCompletion(completed.phase, completed.durationSeconds, completed.completedAtElapsedRealtime, now)
            _events.tryEmit(TimerEvent.PhaseCompleted(completed.phase, result.state.phase))
        }

        if (!result.state.isRunning) {
            stopTicking()
        }

        if (needsPersist(previous, result.state)) persistAsync()
    }

    /**
     * 只有"结构性"变化才值得落盘。
     *
     * 剩余秒数每秒都在变，但快照恢复靠的是 [TimerState.deadlineElapsedRealtime]，
     * 它在整个阶段内是恒定的。逐秒写 DataStore 既没有意义，又是实打实的磁盘开销，
     * 还会和界面动画抢资源。
     */
    private fun needsPersist(old: TimerState, new: TimerState): Boolean =
        old.phase != new.phase ||
            old.isRunning != new.isRunning ||
            old.totalSeconds != new.totalSeconds ||
            old.completedFocusInSet != new.completedFocusInSet ||
            old.deadlineElapsedRealtime != new.deadlineElapsedRealtime

    /** 只把「专注」阶段计入统计；休息不算。 */
    private suspend fun recordCompletion(
        phase: PomodoroPhase,
        durationSeconds: Int,
        completedAtElapsed: Long,
        nowElapsed: Long,
    ) {
        if (phase != PomodoroPhase.FOCUS) return

        // 把单调时钟的完成时刻换算回墙上时间，避免后台停留导致记录时间偏移
        val endedAt = System.currentTimeMillis() - (nowElapsed - completedAtElapsed)

        val activeTaskId = repository.activeTaskId.first()
        val taskTitle = activeTaskId?.let { id ->
            repository.tasks.first().firstOrNull { it.id == id }?.title
        }

        repository.addSession(
            SessionRecord(
                endedAt = endedAt,
                durationSeconds = durationSeconds,
                taskTitle = taskTitle,
            ),
        )

        if (activeTaskId != null) {
            repository.incrementTaskPomodoro(activeTaskId)
        }
    }

    private fun persistAsync() {
        val snapshot = _state.value
        scope.launch {
            runCatching { repository.saveTimerState(snapshot) }
        }
    }

    private companion object {
        const val TICK_INTERVAL_MS = 250L
    }
}
