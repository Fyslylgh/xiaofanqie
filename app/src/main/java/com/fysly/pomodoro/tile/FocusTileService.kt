package com.fysly.pomodoro.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fysly.pomodoro.MainActivity
import com.fysly.pomodoro.PomodoroApplication
import com.fysly.pomodoro.R
import com.fysly.pomodoro.domain.TimerState
import com.fysly.pomodoro.domain.label
import com.fysly.pomodoro.util.formatClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * 控制中心里的计时磁贴：点一下开始专注，再点一下暂停。
 *
 * 磁贴服务与主应用同进程，所以能直接拿到 [PomodoroApplication] 上那个应用级的
 * `TimerController`，和界面用的是同一份状态，不存在两套计时互相打架的问题。
 *
 * 关于后台启动前台服务的限制：Android 12 起，应用在后台默认不能拉起前台服务。
 * 从磁贴点进来属于"用户与应用 UI 交互"，正常情况下会被放行；但不做假设，
 * 一旦启动失败就把界面带到前台兜底——在用户可见的状态下再启动就是合法的。
 */
class FocusTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val controller
        get() = (application as PomodoroApplication).timerController

    override fun onStartListening() {
        super.onStartListening()
        render(controller.state.value)
        // 磁贴可见期间保持跟随，这样倒计时会自己走
        scope.launch {
            controller.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        // 磁贴不可见就停止跟随，别让它在后台白白占着资源
        scope.coroutineContext.cancelChildren()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()

        if (controller.state.value.isRunning) {
            controller.pause()
            return
        }

        controller.start()

        if (!controller.lastServiceStartSucceeded) {
            openAppAsFallback()
        }
    }

    private fun render(state: TimerState) {
        val tile = qsTile ?: return

        tile.state = if (state.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        // 磁贴统一用应用标记（番茄钟），运行与否靠磁贴自己的底色区分。
        // 早先切成播放/暂停图标，结果是磁贴在控制中心里认不出属于哪个应用。
        tile.icon = Icon.createWithResource(this, R.drawable.ic_app_mark)

        // subtitle 从 Android 10 才有，低版本只显示图标和名称
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (state.isRunning) {
                "${state.phase.label} ${formatClock(state.remainingSeconds)}"
            } else {
                "点一下开始专注"
            }
        }

        tile.updateTile()
    }

    /** 前台服务没能起来时的兜底：把应用带到前台，让用户在那里开始。 */
    private fun openAppAsFallback() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
