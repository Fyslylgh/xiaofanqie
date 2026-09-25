package com.fysly.pomodoro.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fysly.pomodoro.domain.TimerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer

private val Context.pomodoroDataStore: DataStore<Preferences> by preferencesDataStore(name = "pomodoro")

/**
 * 全部持久化都走这里：设置、任务、专注记录、计时器快照。
 *
 * 用 DataStore 存 JSON 字符串而不是关系表，是因为数据量很小（几百条任务、几千条记录），
 * 这样既避免了引入 Room/KSP 的构建复杂度，又能享受 DataStore 的原子写入与 Flow 订阅。
 *
 * 序列化器一律显式传入，不用 reified 扩展，避免重载解析出意外的重载。
 */
class PomodoroRepository(context: Context) {

    private val store = context.applicationContext.pomodoroDataStore

    private val json = PomodoroJson

    private val settingsSerializer = AppSettings.serializer()
    private val taskListSerializer = ListSerializer(TaskItem.serializer())
    private val sessionListSerializer = ListSerializer(SessionRecord.serializer())
    private val timerStateSerializer = TimerState.serializer()

    // ---------- 设置 ----------

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        prefs[KEY_SETTINGS].decodeWith(settingsSerializer) { AppSettings() }
    }

    suspend fun currentSettings(): AppSettings = settings.first()

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val current = prefs[KEY_SETTINGS].decodeWith(settingsSerializer) { AppSettings() }
            prefs[KEY_SETTINGS] = json.encodeToString(settingsSerializer, transform(current))
        }
    }

    // ---------- 任务 ----------

    val tasks: Flow<List<TaskItem>> = store.data.map { prefs ->
        prefs[KEY_TASKS].decodeWith(taskListSerializer) { emptyList() }
    }

    suspend fun addTask(
        title: String,
        estimatedPomodoros: Int,
        now: Long = System.currentTimeMillis(),
    ): TaskItem {
        val trimmed = title.trim().ifEmpty { "未命名任务" }
        val task = TaskItem(
            id = "task-$now-${(0..9999).random()}",
            title = trimmed,
            estimatedPomodoros = estimatedPomodoros.coerceIn(1, 99),
            createdAt = now,
        )
        mutateTasks { it + task }
        return task
    }

    suspend fun updateTask(task: TaskItem) = mutateTasks { list ->
        list.map { if (it.id == task.id) task else it }
    }

    suspend fun deleteTask(id: String) {
        mutateTasks { list -> list.filterNot { it.id == id } }
        store.edit { prefs ->
            if (prefs[KEY_ACTIVE_TASK] == id) prefs.remove(KEY_ACTIVE_TASK)
        }
    }

    suspend fun toggleTaskDone(id: String) = mutateTasks { list ->
        list.map { if (it.id == id) it.copy(isDone = !it.isDone) else it }
    }

    /** 给某个任务加一个已完成番茄。 */
    suspend fun incrementTaskPomodoro(id: String) = mutateTasks { list ->
        list.map { if (it.id == id) it.copy(completedPomodoros = it.completedPomodoros + 1) else it }
    }

    private suspend fun mutateTasks(transform: (List<TaskItem>) -> List<TaskItem>) {
        store.edit { prefs ->
            val current = prefs[KEY_TASKS].decodeWith(taskListSerializer) { emptyList() }
            prefs[KEY_TASKS] = json.encodeToString(taskListSerializer, transform(current))
        }
    }

    // ---------- 当前选中的任务 ----------

    val activeTaskId: Flow<String?> = store.data.map { it[KEY_ACTIVE_TASK] }

    suspend fun setActiveTask(id: String?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_TASK) else prefs[KEY_ACTIVE_TASK] = id
        }
    }

    // ---------- 专注记录 ----------

    val sessions: Flow<List<SessionRecord>> = store.data.map { prefs ->
        prefs[KEY_SESSIONS].decodeWith(sessionListSerializer) { emptyList() }
    }

    suspend fun addSession(record: SessionRecord) {
        store.edit { prefs ->
            val current = prefs[KEY_SESSIONS].decodeWith(sessionListSerializer) { emptyList() }
            // 只保留最近 MAX_SESSIONS 条，防止无限增长
            val updated = (current + record).takeLast(MAX_SESSIONS)
            prefs[KEY_SESSIONS] = json.encodeToString(sessionListSerializer, updated)
        }
    }

    suspend fun clearSessions() {
        store.edit { it.remove(KEY_SESSIONS) }
    }

    // ---------- 计时器快照（进程被杀后恢复） ----------

    val timerState: Flow<TimerState?> = store.data.map { prefs ->
        prefs[KEY_TIMER].decodeWith(timerStateSerializer) { null }
    }

    suspend fun saveTimerState(state: TimerState) {
        store.edit { prefs ->
            prefs[KEY_TIMER] = json.encodeToString(timerStateSerializer, state)
        }
    }

    suspend fun clearTimerState() {
        store.edit { it.remove(KEY_TIMER) }
    }

    // ---------- 内部 ----------

    /** 解析失败一律退回默认值，坏数据不应该让应用起不来。 */
    private fun <T> String?.decodeWith(
        serializer: DeserializationStrategy<T>,
        fallback: () -> T,
    ): T {
        if (this == null) return fallback()
        return runCatching { json.decodeFromString(serializer, this) }.getOrElse { fallback() }
    }

    private companion object {
        val KEY_SETTINGS = stringPreferencesKey("settings")
        val KEY_TASKS = stringPreferencesKey("tasks")
        val KEY_SESSIONS = stringPreferencesKey("sessions")
        val KEY_ACTIVE_TASK = stringPreferencesKey("active_task_id")
        val KEY_TIMER = stringPreferencesKey("timer_state")
        const val MAX_SESSIONS = 5000
    }
}
