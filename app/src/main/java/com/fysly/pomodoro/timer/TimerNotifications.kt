package com.fysly.pomodoro.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import com.fysly.pomodoro.MainActivity
import com.fysly.pomodoro.R
import com.fysly.pomodoro.data.ThemeColor
import com.fysly.pomodoro.domain.PomodoroPhase
import com.fysly.pomodoro.domain.TimerState
import com.fysly.pomodoro.domain.label

object TimerNotifications {

    const val CHANNEL_TIMER = "pomodoro_timer"
    const val CHANNEL_ALERT = "pomodoro_alert"

    const val ONGOING_ID = 1001
    const val ALERT_ID = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return

        // 常驻通知：静音、不震动，只用来显示倒计时
        val timerChannel = NotificationChannel(
            CHANNEL_TIMER,
            "计时中",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示当前阶段与剩余时间"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        // 阶段结束提醒：声音和震动由应用自己控制（受设置里的开关约束），
        // 所以渠道本身保持静音，避免用户关掉开关后系统仍然出声。
        val alertChannel = NotificationChannel(
            CHANNEL_ALERT,
            "阶段结束提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "专注或休息结束时提醒你"
            setShowBadge(true)
            enableVibration(false)
            setSound(null, null)
        }

        manager.createNotificationChannels(listOf(timerChannel, alertChannel))
    }

    /**
     * 计时中的常驻通知。
     *
     * 只用最普通的原生元素：小图标、标题、正文、系统 chronometer、细进度条、两个操作按钮。
     * 不套自定义样式、不染背景，交给系统按各家自己的模板去画。
     *
     * 这里曾经为了接入 OPPO 流体云加过一整套实时活动的东西——ProgressStyle、
     * setColorized、setShortCriticalText、进度条上的滑块图标、setRequestPromotedOngoing。
     * 实测那套在 ColorOS 上反而把通知变成一张大色块卡片（setColorized 会把 setColor 的颜色
     * 铺满整个背景），而系统依旧没有把它提升为实时活动。既然这条路走不通，就整套撤掉。
     * 调研结论保留在 README 的「流体云」一节，将来若要重做不必重新摸索。
     */
    fun buildOngoing(
        context: Context,
        state: TimerState,
        taskTitle: String?,
        themeColor: ThemeColor,
        mediaToken: MediaSessionCompat.Token? = null,
    ): Notification {
        val total = state.totalSeconds.coerceAtLeast(1)
        val elapsed = (state.totalSeconds - state.remainingSeconds).coerceIn(0, total)
        val accent = NotificationAccent.of(state.phase, themeColor)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleAction = if (state.isRunning) "暂停" else "继续"
        val toggleIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, TimerService::class.java).setAction(TimerService.ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val skipIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, TimerService::class.java).setAction(TimerService.ACTION_SKIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resetIntent = PendingIntent.getService(
            context,
            3,
            Intent(context, TimerService::class.java).setAction(TimerService.ACTION_RESET),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 标题只放阶段名，正文只放任务或状态，两者都不含时间。
        // 时间统一交给系统的 chronometer——它会自己持续走动；
        // 而自己拼出来的时间字符串只在下发通知的那一刻是对的，之后就一直停在原处。
        val title = state.phase.label
        val subtitle = when {
            !state.isRunning -> "已暂停"
            state.phase == PomodoroPhase.SHORT_BREAK -> "起来动一动"
            state.phase == PomodoroPhase.LONG_BREAK -> "这一组完成了，好好休息"
            taskTitle != null -> taskTitle
            else -> "保持专注"
        }

        return NotificationCompat.Builder(context, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_app_mark)
            // 只作为系统的强调色使用：标准模板拿它给应用图标垫底色，
            // 不会影响通知背景（背景染色要 setColorized，那才是让通知变色的元凶）
            .setColor(accent)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // 暂停时不显示时间，因为此时 chronometer 已经停了，留着一个不动的数字反而费解
            .setShowWhen(state.isRunning)
            .setUsesChronometer(state.isRunning)
            .setChronometerCountDown(state.isRunning)
            .setWhen(System.currentTimeMillis() + state.remainingSeconds * 1000L)
            .setProgress(total, elapsed, false)
            // 三个操作：重置、暂停/继续、跳过。
            // 媒体卡片的紧凑视图只显示"操作图标"，图标为 0 会渲染成一片空白，
            // 所以这里必须给真实图标。
            .addAction(R.drawable.ic_notif_reset, "重置", resetIntent)
            .addAction(
                if (state.isRunning) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                toggleAction,
                toggleIntent,
            )
            .addAction(R.drawable.ic_notif_skip, "跳过", skipIntent)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                // 绑定媒体会话之后，这条通知同时成为系统媒体卡片的载体：
                // 锁屏播放器、快捷设置里的播放控件、以及 OPPO 的音乐流体云都从这里取内容。
                if (mediaToken != null) {
                    setStyle(
                        MediaNotificationCompat.MediaStyle()
                            .setMediaSession(mediaToken)
                            // 三个都放进紧凑视图，正好占满媒体卡片的一排按钮
                            .setShowActionsInCompactView(0, 1, 2),
                    )
                }
            }
            .build()
    }

    /** 阶段结束的提醒通知。 */
    fun buildAlert(
        context: Context,
        completed: PomodoroPhase,
        next: PomodoroPhase,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (completed) {
            PomodoroPhase.FOCUS -> "专注结束"
            PomodoroPhase.SHORT_BREAK -> "短休息结束"
            PomodoroPhase.LONG_BREAK -> "长休息结束"
        }
        // 不写死具体分钟数：时长是用户可配的，写死会和设置对不上
        val message = when (next) {
            PomodoroPhase.FOCUS -> "该回到专注了"
            PomodoroPhase.SHORT_BREAK -> "起来动一动"
            PomodoroPhase.LONG_BREAK -> "这一组完成了，好好休息一下"
        }

        return NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_app_mark)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
    }

    fun cancelAlert(context: Context) {
        context.getSystemService<NotificationManager>()?.cancel(ALERT_ID)
    }
}
