package com.fysly.pomodoro.timer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fysly.pomodoro.PomodoroApplication
import com.fysly.pomodoro.data.MediaArtwork
import com.fysly.pomodoro.data.PomodoroRepository
import com.fysly.pomodoro.data.ThemeColor
import com.fysly.pomodoro.domain.PomodoroPhase
import com.fysly.pomodoro.domain.TimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 计时期间的前台服务：保证进程不被系统回收、并在通知栏持续显示倒计时。
 *
 * 服务本身不持有任何计时逻辑——所有状态都在 [TimerController] 里，
 * 这里只做三件事：把状态映射成通知、响应通知上的按钮、阶段结束时提醒用户。
 */
class TimerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: TimerController
    private lateinit var repository: PomodoroRepository
    private val alerts by lazy { Alerts(this) }

    private var activeTaskTitle: String? = null
    private var lastKey: NotificationKey? = null
    private var foregroundStarted = false
    private var lastForegroundType = 0

    /** 暂停宽限期的倒计时任务；恢复播放时取消 */
    private var shutdownJob: Job? = null

    /**
     * 媒体卡片模式下的媒体会话。设置里关掉这项时为 null，
     * 通知就退化成一条普通的常驻通知。
     */
    private var mediaSession: MediaSessionController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as PomodoroApplication
        controller = app.timerController
        repository = app.repository
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> controller.toggle()
            ACTION_SKIP -> controller.skip()
            ACTION_RESET -> controller.reset()
            ACTION_DISMISS_ALERT -> NotificationManagerCompat.from(this)
                .cancel(TimerNotifications.ALERT_ID)

            else -> Unit
        }

        val state = controller.state.value

        // 先让媒体会话进入播放状态，再进前台：媒体播放型前台服务要求启动时确实在播放
        syncMedia(state)

        // 必须先满足 startForegroundService 的契约再决定去留：
        // 用 startForegroundService 拉起来的服务如果没调用 startForeground 就自己停掉，
        // 系统会抛 ForegroundServiceDidNotStartInTimeException 把进程干掉。
        // 「开始后立刻暂停」正好会走到这里，所以这一句不能省。
        promoteToForeground(state)

        if (!state.isRunning) {
            // 不是立刻收摊，而是走宽限期：暂停后媒体控件要留一会儿
            scheduleShutdown()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alerts.stop()
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        foregroundStarted = false
        super.onDestroy()
    }

    // ---------- 内部 ----------

    private fun observe() {
        scope.launch {
            controller.state.collect { state ->
                if (!state.isRunning) {
                    // 暂停后**不**立刻收摊。
                    //
                    // 音乐类应用暂停时，媒体控件会继续留在锁屏和控制中心一段时间，
                    // 用户从那里点一下就能接着放。之前我们是一停就关服务、释放会话，
                    // 卡片瞬间消失，体验上就不像"播放器"了。
                    // 现在给一个宽限期：期间控件还在，点继续就接着走。
                    if (foregroundStarted) {
                        switchForegroundType(state)
                        scheduleShutdown()
                    }
                    return@collect
                }
                cancelShutdown()

                val key = NotificationKey(
                    phase = state.phase,
                    totalSeconds = state.totalSeconds,
                    running = state.isRunning,
                    taskTitle = activeTaskTitle,
                    themeColor = controller.settings.value.themeColor,
                    mediaCard = controller.settings.value.mediaCardEnabled,
                    mediaArtwork = controller.settings.value.mediaArtwork,
                    // 通知里的进度条靠重新下发通知来推进，系统不会自己动它，
                    // 所以剩余秒数每变一次就刷新一次。
                    progressTick = state.remainingSeconds,
                )
                if (key == lastKey) return@collect
                lastKey = key
                // 会话要先跟上状态，通知才能带着正确的媒体信息下发
                syncMedia(state)
                postOngoing(state)
            }
        }

        // 当前任务的标题会影响通知文案，单独订阅
        scope.launch {
            combine(repository.activeTaskId, repository.tasks) { id, tasks ->
                tasks.firstOrNull { it.id == id }?.title
            }.collect { title ->
                if (title != activeTaskTitle) {
                    activeTaskTitle = title
                    lastKey = null // 强制刷新一次
                    val state = controller.state.value
                    if (state.isRunning) postOngoing(state)
                }
            }
        }

        scope.launch {
            controller.events.collect { event ->
                if (event is TimerEvent.PhaseCompleted) {
                    onPhaseCompleted(event)
                }
            }
        }
    }

    private fun onPhaseCompleted(event: TimerEvent.PhaseCompleted) {
        alerts.play(controller.settings.value)

        val notification = TimerNotifications.buildAlert(this, event.completed, event.next)
        // Android 13+ 未授予通知权限时 notify 会静默失败，声音和震动仍然有效
        runCatching {
            NotificationManagerCompat.from(this).notify(TimerNotifications.ALERT_ID, notification)
        }
    }

    private fun postOngoing(state: TimerState) {
        val notification = buildNotification(state)
        if (foregroundStarted) {
            runCatching {
                NotificationManagerCompat.from(this).notify(TimerNotifications.ONGOING_ID, notification)
            }
        } else {
            promoteToForeground(state, notification)
        }
    }

    private fun buildNotification(state: TimerState) = TimerNotifications.buildOngoing(
        context = this,
        state = state,
        taskTitle = activeTaskTitle,
        themeColor = controller.settings.value.themeColor,
        mediaToken = mediaSession?.token,
    )

    /**
     * 让媒体会话跟上计时状态。
     *
     * 关掉设置时会主动释放会话，通知随之退化成普通的常驻通知。
     * 会话上的播放/暂停/跳过回调直接转给 [TimerController]，
     * 所以锁屏播放器上的按钮和通知里的按钮行为完全一致。
     */
    private fun syncMedia(state: TimerState) {
        if (!controller.settings.value.mediaCardEnabled) {
            mediaSession?.release()
            mediaSession = null
            return
        }

        val session = mediaSession ?: MediaSessionController(
            context = this,
            playAction = { controller.start() },
            pauseAction = { controller.pause() },
            skipAction = { controller.skip() },
            resetAction = { controller.reset() },
        ).also { mediaSession = it }

        session.sync(
            state = state,
            taskTitle = activeTaskTitle,
            themeColor = controller.settings.value.themeColor,
            artworkMode = controller.settings.value.mediaArtwork,
        )
    }

    private fun promoteToForeground(
        state: TimerState,
        notification: android.app.Notification? = null,
    ) {
        val n = notification ?: buildNotification(state)
        val type = foregroundTypeFor(state)
        runCatching {
            ServiceCompat.startForeground(this, TimerNotifications.ONGOING_ID, n, type)
            foregroundStarted = true
            lastForegroundType = type
        }
    }

    /**
     * 当前状态该用哪种前台服务类型。
     *
     * 媒体卡片模式下要声明成媒体播放型，否则系统不会把这个会话当成正在播放的媒体，
     * 锁屏播放器和各家音乐卡片都不会理它。但只有在真的播放时才能这么声明——
     * mediaPlayback 型前台服务要求确实处于播放状态，「开始后立刻暂停」那条路径
     * 必须退回 specialUse，否则 startForeground 会被系统拒绝。
     */
    private fun foregroundTypeFor(state: TimerState): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (mediaSession != null && state.isRunning) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        } else {
            0
        }

    /**
     * 暂停后把前台服务类型从 mediaPlayback 退回 specialUse。
     * 类型变了必须重新调一次 startForeground 才生效。
     */
    private fun switchForegroundType(state: TimerState) {
        val desired = foregroundTypeFor(state)
        if (desired == lastForegroundType) return
        val n = buildNotification(state)
        runCatching {
            ServiceCompat.startForeground(this, TimerNotifications.ONGOING_ID, n, desired)
            lastForegroundType = desired
        }
    }

    /**
     * 暂停宽限期。
     *
     * 音乐类应用暂停后媒体控件会继续留一会儿，用户从锁屏或控制中心点一下就能接着放。
     * 之前我们是一停就关服务、释放会话，卡片瞬间消失，用起来不像播放器。
     * 现在暂停后服务、常驻通知和媒体会话都再活一段时间，超时才真正收摊。
     */
    private fun scheduleShutdown() {
        if (shutdownJob?.isActive == true) return
        shutdownJob = scope.launch {
            delay(PAUSE_LINGER_MS)
            if (!controller.state.value.isRunning) shutdown()
        }
    }

    private fun cancelShutdown() {
        shutdownJob?.cancel()
        shutdownJob = null
    }

    private fun shutdown() {
        cancelShutdown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private data class NotificationKey(
        val phase: PomodoroPhase,
        val totalSeconds: Int,
        val running: Boolean,
        val taskTitle: String?,
        val themeColor: ThemeColor,
        val mediaCard: Boolean,
        val mediaArtwork: MediaArtwork,
        val progressTick: Int,
    )

    companion object {
        const val ACTION_TOGGLE = "com.fysly.pomodoro.action.TOGGLE"
        const val ACTION_SKIP = "com.fysly.pomodoro.action.SKIP"
        const val ACTION_RESET = "com.fysly.pomodoro.action.RESET"
        const val ACTION_DISMISS_ALERT = "com.fysly.pomodoro.action.DISMISS_ALERT"

        /** 暂停后媒体控件的保留时长 */
        private const val PAUSE_LINGER_MS = 10 * 60 * 1000L

        /**
         * 启动前台服务。计时开始时由 [TimerController] 调用。
         *
         * 返回是否成功：Android 12+ 不允许应用在后台随意启动前台服务，
         * 从快捷设置磁贴点进来通常会被放行，但不能假设一定成功——
         * 磁贴需要知道结果，好在失败时把界面带到前台兜底。
         *
         * 没有对应的 stop()：服务自己订阅计时状态，一旦计时停止就自行收摊，
         * 这样就不会出现"服务被拉起来却还没 startForeground 就被外部停掉"的竞态。
         */
        fun start(context: Context): Boolean =
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
            }.isSuccess
    }
}
