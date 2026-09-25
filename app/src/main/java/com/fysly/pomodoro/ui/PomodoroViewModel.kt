package com.fysly.pomodoro.ui

import androidx.compose.runtime.Stable
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fysly.pomodoro.PomodoroApplication
import com.fysly.pomodoro.data.AppSettings
import com.fysly.pomodoro.data.CustomArtworkStore
import com.fysly.pomodoro.data.MediaArtwork
import com.fysly.pomodoro.data.PomodoroRepository
import com.fysly.pomodoro.data.SessionRecord
import com.fysly.pomodoro.data.StatsCalculator
import com.fysly.pomodoro.data.StatsSummary
import com.fysly.pomodoro.data.TaskItem
import com.fysly.pomodoro.data.ThemeColor
import com.fysly.pomodoro.data.ThemeMode
import com.fysly.pomodoro.domain.TimerState
import com.fysly.pomodoro.timer.TimerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 标成 [Stable] 不是随手加的。
 *
 * 这个 ViewModel 持有的 [TimerController] 里有 `var` 字段，Compose 会把整条引用链判定为不稳定，
 * 于是 `PomodoroRoot(viewModel)` 无法跳过重组——计时运行时每秒都会把整棵页面树重组一遍，
 * 正好压在转场动画上，表现就是卡顿。
 *
 * 这里能安全声明为稳定，是因为对外只暴露 StateFlow 和只读方法，
 * 界面不会直接读它的可变字段；状态变化一律通过 Flow 驱动更细粒度的重组。
 */
@Stable
class PomodoroViewModel(
    private val app: PomodoroApplication,
) : ViewModel() {

    private val repository: PomodoroRepository = app.repository
    private val timerController: TimerController = app.timerController

    // ---------- 计时 ----------

    val timerState: StateFlow<TimerState> = timerController.state

    /**
     * "计时中且用户开了屏幕常亮"。
     *
     * 单独抽成一个 Boolean 流，是为了让 Activity 不必订阅整个计时状态——
     * 否则剩余秒数每秒变化一次，Activity 的整个 setContent 都会跟着重组。
     */
    val keepScreenOn: StateFlow<Boolean> = combine(
        timerController.state,
        repository.settings,
    ) { state, settings -> settings.keepScreenOn && state.isRunning }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleTimer() = timerController.toggle()

    fun resetTimer() = timerController.reset()

    fun resetAll() = timerController.resetAll()

    fun skipPhase() = timerController.skip()

    /** Activity 进入后台时把当前计时状态落盘。 */
    fun onEnterBackground() = timerController.persistNow()

    // ---------- 任务 ----------

    val tasks: StateFlow<List<TaskItem>> = repository.tasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeTaskId: StateFlow<String?> = repository.activeTaskId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前选中的任务，计时页与通知都用它。 */
    val activeTask: StateFlow<TaskItem?> = combine(activeTaskId, tasks) { id, list ->
        list.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun addTask(title: String, estimatedPomodoros: Int) {
        viewModelScope.launch { repository.addTask(title, estimatedPomodoros) }
    }

    fun updateTask(task: TaskItem) {
        viewModelScope.launch { repository.updateTask(task) }
    }

    fun deleteTask(id: String) {
        viewModelScope.launch { repository.deleteTask(id) }
    }

    fun toggleTaskDone(id: String) {
        viewModelScope.launch { repository.toggleTaskDone(id) }
    }

    /** 再点一次已选中的任务 = 取消选中。 */
    fun selectTask(id: String) {
        viewModelScope.launch {
            repository.setActiveTask(if (activeTaskId.value == id) null else id)
        }
    }

    fun clearActiveTask() {
        viewModelScope.launch { repository.setActiveTask(null) }
    }

    // ---------- 统计 ----------

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val stats: StateFlow<StatsSummary> = combine(
        repository.sessions,
        repository.settings,
    ) { sessions, settings ->
        StatsCalculator.summarize(
            sessions = sessions,
            now = System.currentTimeMillis(),
            dailyGoal = settings.dailyGoal,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsSummary())

    val recentSessions: StateFlow<List<SessionRecord>> = repository.sessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearHistory() {
        viewModelScope.launch { repository.clearSessions() }
    }

    // ---------- 设置 ----------

    fun setFocusMinutes(minutes: Int) = updateSettings { it.copy(focusMinutes = minutes) }

    fun setShortBreakMinutes(minutes: Int) = updateSettings { it.copy(shortBreakMinutes = minutes) }

    fun setLongBreakMinutes(minutes: Int) = updateSettings { it.copy(longBreakMinutes = minutes) }

    fun setCyclesBeforeLongBreak(cycles: Int) =
        updateSettings { it.copy(cyclesBeforeLongBreak = cycles) }

    fun setAutoStartBreaks(enabled: Boolean) = updateSettings { it.copy(autoStartBreaks = enabled) }

    fun setAutoStartFocus(enabled: Boolean) = updateSettings { it.copy(autoStartFocus = enabled) }

    fun setSoundEnabled(enabled: Boolean) = updateSettings { it.copy(soundEnabled = enabled) }

    fun setVibrationEnabled(enabled: Boolean) =
        updateSettings { it.copy(vibrationEnabled = enabled) }

    fun setKeepScreenOn(enabled: Boolean) = updateSettings { it.copy(keepScreenOn = enabled) }

    fun setThemeMode(mode: ThemeMode) = updateSettings { it.copy(themeMode = mode) }

    fun setThemeColor(color: ThemeColor) = updateSettings { it.copy(themeColor = color) }

    fun setDailyGoal(goal: Int) = updateSettings { it.copy(dailyGoal = goal) }

    fun setMediaCardEnabled(enabled: Boolean) =
        updateSettings { it.copy(mediaCardEnabled = enabled) }

    fun setMediaArtwork(mode: MediaArtwork) = updateSettings { it.copy(mediaArtwork = mode) }

    // ---------- 媒体卡片自定义封面 ----------

    private val _hasCustomArtwork = MutableStateFlow(CustomArtworkStore.exists(app))
    val hasCustomArtwork: StateFlow<Boolean> = _hasCustomArtwork.asStateFlow()

    /**
     * 把相册里的图片导入成媒体卡片封面。
     *
     * 做成挂起函数而不是"触发后不管"，是为了让界面能拿到成功与否——
     * 解码失败、图片损坏这类情况用户需要知道，否则会以为换了图却没生效。
     */
    suspend fun importCustomArtwork(uri: Uri): Boolean {
        val ok = CustomArtworkStore.importFrom(app, uri)
        if (ok) {
            _hasCustomArtwork.value = true
            repository.updateSettings { it.copy(mediaArtwork = MediaArtwork.CUSTOM) }
        }
        return ok
    }

    fun clearCustomArtwork() {
        CustomArtworkStore.clear(app)
        _hasCustomArtwork.value = false
        updateSettings {
            if (it.mediaArtwork == MediaArtwork.CUSTOM) {
                it.copy(mediaArtwork = MediaArtwork.GRADIENT)
            } else {
                it
            }
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.updateSettings(transform) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as PomodoroApplication
                PomodoroViewModel(app)
            }
        }
    }
}
