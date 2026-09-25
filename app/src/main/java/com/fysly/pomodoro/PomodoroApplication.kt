package com.fysly.pomodoro

import android.app.Application
import com.fysly.pomodoro.data.PomodoroRepository
import com.fysly.pomodoro.timer.TimerController
import com.fysly.pomodoro.timer.TimerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PomodoroApplication : Application() {

    /** 应用级作用域：计时循环要活在 Activity 生命周期之外。 */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository: PomodoroRepository by lazy { PomodoroRepository(this) }

    val timerController: TimerController by lazy {
        TimerController(
            context = this,
            repository = repository,
            scope = applicationScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        TimerNotifications.ensureChannels(this)
        applicationScope.launch { timerController.initialize() }
    }
}
