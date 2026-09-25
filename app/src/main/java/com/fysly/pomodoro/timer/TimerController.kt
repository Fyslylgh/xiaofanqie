package com.fysly.pomodoro.timer

import android.content.Context
import android.os.SystemClock
import com.fysly.pomodoro.data.AppSettings
import com.fysly.pomodoro.data.PomodoroRepository
import com.fysly.pomodoro.data.SessionRecord
import com.fysly.pomodoro.data.toTimerConfig
import com.fysly.pomodoro.domain.CompletedPhase
import com.fysly.pomodoro.domain.PomodoroEngine
import com.fysly.pomodoro.domain.PomodoroPhase
import com.fysly.pomodoro.domain.TimerConfig
import com.fysly.pomodoro.domain.TimerState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
 *
 * **所有对状态的读写都在 [stateLock] 里串行执行。** 这一点不是可有可无的：
 * 改状态的入口有四个不同的线程——
 *   - 每秒 4 次的 tick 协程（跑在应用级 Dispatchers.Default 上）
 *   - 界面按钮（主线程）
 *   - 通知上的按钮（也是主线程）
 *   - **媒体会话的回调（系统 Binder 线程）**，锁屏、控制中心、OPPO 流体云上的按钮都走这里
 *
 * 这些入口做的都是"读当前状态 -> 用引擎算出新状态 -> 写回"这三步。不加锁的话，
 * 一次 tick 的写回可以把用户刚按下的暂停整个盖掉——表现就是媒体控件上的
 * 暂停/切换"有时候按了没反应"。加上锁之后，用户操作和 tick 不会交错，
 * 谁先拿到锁谁先改，后改的基于前一次的结果继续算。
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

    /** 串行化所有状态读写；见类注释。 */
    private val stateLock = ReentrantLock()

    private var tickJob: Job? = null
    private val initialized = AtomicBoolean(false)

    /**
     * 最近一次尝试拉起前台服务是否成功。
     *
     * 快捷设置磁贴靠它判断要不要把界面带到前台兜底：Android 12+ 限制应用在后台启动
     * 前台服务，磁贴点击一般会被放行，但万一被拦下，计时会在没有前台服务的情况下裸跑，
     * 随时可能被系统回收。
     */
    @Volatile
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
        val restored = stateLock.withLock {
            val r = saved
                // 走 restore 而不是直接 advance：它会把跨重启的脏快照挡掉，
                // 详见 PomodoroEngine.restore 的注释
                ?.let { engine.restore(it, cfg, elapsedClock()) }
                ?: engine.initialState(cfg)
            _state.value = r
            r
        }

        if (restored.isRunning) {
            startTicking(restored)
            TimerService.start(context)
        }

        // 设置变化时同步到当前状态（运行中的阶段不会被改动）
        scope.launch {
            repository.settings.collect { latest ->
                _settings.value = latest
                stateLock.withLock {
                    _state.value = engine.applyConfig(_state.value, latest.toTimerConfig())
                }
            }
        }
    }

    // ---------- 用户操作 ----------

    fun start() {
        val next = stateLock.withLock {
            val current = _state.value
            if (current.isRunning) return
            // 阶段已经走完（remaining == 0）时，engine.start 会原样返回——那一按就等于没反应。
            // 媒体卡片上的"播放"正好会撞上这种情况：一个番茄自然结束、自动开始又关着的时候，
            // 卡片上留着播放按钮，按下去却什么都不发生。这里先把阶段重置回起点再开始。
            val base = if (current.remainingSeconds <= 0) engine.reset(current, config) else current
            val updated = engine.start(base, elapsedClock())
            _state.value = updated
            updated
        }
        if (next.isRunning) {
            startTicking(next)
            lastServiceStartSucceeded = TimerService.start(context)
            persistAsync(next)
        }
    }

    fun pause() {
        val paused = stateLock.withLock {
            val current = _state.value
            if (!current.isRunning) return
            val updated = engine.pause(current, config, elapsedClock())
            _state.value = updated
            updated
        }
        stopTicking()
        persistAsync(paused)
    }

    fun toggle() {
        if (state.value.isRunning) pause() else start()
    }

    /** 重置当前阶段。 */
    fun reset() {
        val next = stateLock.withLock {
            val updated = engine.reset(_state.value, config)
            _state.value = updated
            updated
        }
        stopTicking()
        persistAsync(next)
    }

    /** 整组重来：回到第一个专注，已完成计数清零。 */
    fun resetAll() {
        val next = stateLock.withLock {
            val updated = engine.resetAll(config)
            _state.value = updated
            updated
        }
        stopTicking()
        persistAsync(next)
    }

    /**
     * 跳过当前阶段，进入下一个（不计入统计，也不推进组内计数）。
     *
     * 整段都在锁里同步做完：媒体控件上的"下一首"按下时，用户期望的是立刻看到阶段变了，
     * 而不是等一个协程被调度起来。中途 await 也会给 tick 留出插入的机会。
     */
    fun skip() {
        val result = stateLock.withLock {
            // 先结算已经流逝的时间：万一阶段刚好在这个瞬间走完，不能把那次完成丢掉
            val settled = settleDuePhases()
            val now = elapsedClock()
            val next = engine.skip(_state.value, config, now)
            _state.value = next
            settled to next
        }
        val (completed, next) = result

        for (item in completed) {
            recordCompletionAsync(item)
        }

        if (next.isRunning) {
            startTicking(next)
            TimerService.start(context)
        } else {
            stopTicking()
        }
        persistAsync(next)
    }

    /** 界面进入后台或即将被销毁时，把当前状态落盘。 */
    fun persistNow() {
        persistAsync(state.value)
    }

    // ---------- 内部 ----------

    /**
     * 启动 tick 循环。必须在 [stateLock] 之外调用。
     *
     * [snapshot] 是调用方刚写进去的那份状态：只有它还在跑的时候才需要 tick
     * （跳过阶段时会出现"刚跳过就已经停了"的情况，那时不必起循环）。
     */
    private fun startTicking(snapshot: TimerState = state.value) {
        if (!snapshot.isRunning) return
        stateLock.withLock {
            if (tickJob?.isActive == true) return
            tickJob = scope.launch {
                while (isActive) {
                    delay(TICK_INTERVAL_MS)
                    doTick()
                    if (!state.value.isRunning) break
                }
            }
        }
    }

    private fun stopTicking() {
        stateLock.withLock {
            tickJob?.cancel()
            tickJob = null
        }
    }

    private fun doTick() {
        val tick = stateLock.withLock {
            val previous = _state.value
            val result = engine.advance(previous, config, elapsedClock())
            if (result.completed.isEmpty() && result.state == previous) return
            _state.value = result.state
            TickOutcome(
                completed = result.completed,
                running = result.state.isRunning,
                persist = needsPersist(previous, result.state),
                phase = result.state.phase,
            )
        }
        for (item in tick.completed) {
            recordCompletionAsync(item)
            _events.tryEmit(TimerEvent.PhaseCompleted(item.phase, tick.phase))
        }
        if (!tick.running) stopTicking()
        if (tick.persist) persistAsync(state.value)
    }

    private class TickOutcome(
        val completed: List<CompletedPhase>,
        val running: Boolean,
        val persist: Boolean,
        val phase: PomodoroPhase,
    )

    /**
     * 把已经到点的阶段补上（只在锁内调用）。
     *
     * 返回补出来的完成事件；调用方负责在锁外记录统计并发事件——
     * 落盘是挂起操作，放在锁里会把 tick 和界面按钮一起堵住。
     */
    private fun settleDuePhases(): List<CompletedPhase> {
        val previous = _state.value
        val result = engine.advance(previous, config, elapsedClock())
        if (result.completed.isEmpty() && result.state == previous) return emptyList()
        _state.value = result.state
        return result.completed
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

    /** 把「专注」阶段计入统计；休息不算。 */
    private fun recordCompletionAsync(item: CompletedPhase) {
        if (item.phase != PomodoroPhase.FOCUS) return

        val nowElapsed = elapsedClock()
        scope.launch {
            // 把单调时钟的完成时刻换算回墙上时间，避免后台停留导致记录时间偏移
            val endedAt = System.currentTimeMillis() - (nowElapsed - item.completedAtElapsedRealtime)

            val activeTaskId = repository.activeTaskId.first()
            val taskTitle = activeTaskId?.let { id ->
                repository.tasks.first().firstOrNull { it.id == id }?.title
            }

            repository.addSession(
                SessionRecord(
                    endedAt = endedAt,
                    durationSeconds = item.durationSeconds,
                    taskTitle = taskTitle,
                ),
            )

            if (activeTaskId != null) {
                repository.incrementTaskPomodoro(activeTaskId)
            }
        }
    }

    private fun persistAsync(snapshot: TimerState) {
        scope.launch {
            runCatching { repository.saveTimerState(snapshot) }
        }
    }

    private companion object {
        const val TICK_INTERVAL_MS = 250L
    }
}
