package com.fysly.pomodoro.timer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.fysly.pomodoro.R
import com.fysly.pomodoro.data.CustomArtworkStore
import com.fysly.pomodoro.data.MediaArtwork
import com.fysly.pomodoro.data.ThemeColor
import com.fysly.pomodoro.domain.PomodoroPhase
import com.fysly.pomodoro.domain.TimerState
import com.fysly.pomodoro.domain.label

/**
 * 把计时器伪装成一个"正在播放的媒体会话"。
 *
 * 为什么要这么做：OPPO 的音乐流体云、各家系统的锁屏播放器与媒体卡片，读的都是标准的
 * `MediaSession`。系统并不区分这是音乐还是别的东西，只要是一个活跃的、状态为播放的会话，
 * 它就会把会话的标题、封面、进度和操作按钮画到媒体卡片上。于是倒计时就能借这个入口显示出来。
 *
 * 顺带还有两个好处：一是系统对"正在播放音频"的应用保活更宽松；二是媒体卡片本身就带进度条。
 *
 * 代价也要说清楚：
 * - 会和其他音乐应用争抢媒体控件。同一时刻系统只把"最活跃的那个会话"放进卡片，
 *   计时开始后音乐卡片会被挤掉（音乐重新播放能抢回来）。
 * - 静音音轨是真实播放的，音频管线全程运行，一个番茄会实打实耗一点电。
 *
 * 实测前想清楚这几点，所以它在设置里是一个可以关掉的开关。
 */
class MediaSessionController(
    private val context: Context,
    private val playAction: () -> Unit,
    private val pauseAction: () -> Unit,
    private val skipAction: () -> Unit,
) {

    private var session: MediaSessionCompat? = null
    private var player: MediaPlayer? = null
    private var isPlaying = false

    private var artwork: Bitmap? = null
    private var artworkKey: String? = null

    /** 供 MediaStyle 通知绑定用；会话没建起来时为 null。 */
    val token: MediaSessionCompat.Token?
        get() = session?.sessionToken

    /** 首次调用会建立会话，之后每次调用都同步一次元数据与播放状态。 */
    fun sync(
        state: TimerState,
        taskTitle: String?,
        themeColor: ThemeColor,
        artworkMode: MediaArtwork,
    ) {
        if (session == null) createSession()
        applyState(state, taskTitle, themeColor, artworkMode)
    }

    fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        isPlaying = false
        runCatching { session?.isActive = false }
        runCatching { session?.release() }
        session = null
        artwork = null
        artworkKey = null
    }

    // ---------- 内部 ----------

    private fun createSession() {
        val created = MediaSessionCompat(context, SESSION_TAG).apply {
            // 注意别把这里的参数名写成 onPlay/onPause——那会和 Callback 自己的方法同名，
            // 于是在 override 里调用它就变成调用自己，Kotlin 会直接报"递归类型检查"
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = playAction()
                override fun onPause() = pauseAction()
                override fun onStop() = pauseAction()
                override fun onSkipToNext() = skipAction()
                // 卡片上不显示上一首，但回调仍然实现掉，避免系统等一个永远不会来的响应
                override fun onSkipToPrevious() = Unit
            })
            isActive = true
        }
        session = created
    }

    private fun applyState(
        state: TimerState,
        taskTitle: String?,
        themeColor: ThemeColor,
        artworkMode: MediaArtwork,
    ) {
        val s = session ?: return

        val title = state.phase.label
        val subtitle = when {
            !state.isRunning -> "已暂停"
            state.phase == PomodoroPhase.SHORT_BREAK -> "短休息"
            state.phase == PomodoroPhase.LONG_BREAK -> "长休息"
            taskTitle != null -> taskTitle
            else -> "保持专注"
        }

        val totalMillis = state.totalSeconds.coerceAtLeast(1) * 1000L
        val elapsedMillis =
            (state.totalSeconds - state.remainingSeconds).coerceIn(0, state.totalSeconds) * 1000L

        val accent = NotificationAccent.of(state.phase, themeColor)
        val cover = artworkFor(artworkMode, accent)

        s.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "小番茄")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, totalMillis)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
                .build(),
        )

        // 媒体卡片的进度条用的是 position / duration。
        // 会话只能"往前走"，做不出倒计时，所以卡片上会显示"已过去 / 总时长"。
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    if (state.isRunning) {
                        PlaybackStateCompat.STATE_PLAYING
                    } else {
                        PlaybackStateCompat.STATE_PAUSED
                    },
                    elapsedMillis,
                    if (state.isRunning) 1f else 0f,
                )
                .build(),
        )

        if (state.isRunning) startPlayer() else pausePlayer()
    }

    // ---------- 封面 ----------

    /**
     * 按需渲染并缓存封面。
     *
     * 缓存键里带上了自定义文件的时间戳，所以用户在设置里换了图片之后，
     * 不需要额外的通知机制，下一次同步就会重新加载。
     */
    private fun artworkFor(mode: MediaArtwork, accent: Int): Bitmap? {
        val key = when (mode) {
            MediaArtwork.CUSTOM -> "custom:${CustomArtworkStore.lastModified(context)}"
            MediaArtwork.APP_ICON -> "icon"
            MediaArtwork.GRADIENT -> "gradient:$accent"
        }
        if (key != artworkKey) {
            artwork = render(mode, accent)
            artworkKey = key
        }
        return artwork
    }

    private fun render(mode: MediaArtwork, accent: Int): Bitmap? = when (mode) {
        // 自定义图片没设置或读不出来时退回渐变，而不是给一张空白封面
        MediaArtwork.CUSTOM -> CustomArtworkStore.load(context) ?: renderGradient(accent)
        MediaArtwork.APP_ICON -> renderLauncherIcon() ?: renderGradient(accent)
        MediaArtwork.GRADIENT -> renderGradient(accent)
    }

    /** 对角渐变：从阶段强调色过渡到一个压暗的同色。 */
    private fun renderGradient(accent: Int): Bitmap {
        val size = ARTWORK_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                accent,
                darken(accent, GRADIENT_DARKEN),
                Shader.TileMode.CLAMP,
            )
        }
        Canvas(bitmap).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return bitmap
    }

    private fun darken(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )

    /**
     * 媒体卡片需要一个方形封面，这里把启动图标画成位图。
     * 图标是自适应图标（XML），BitmapFactory 解不了，只能走 Drawable 绘制。
     */
    private fun renderLauncherIcon(): Bitmap? = runCatching {
        val size = ARTWORK_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()

    // ---------- 静音音轨 ----------

    private fun startPlayer() {
        if (isPlaying) return
        val p = player ?: createPlayer() ?: return
        runCatching { p.start() }
        isPlaying = true
    }

    private fun pausePlayer() {
        if (!isPlaying) return
        runCatching { player?.pause() }
        isPlaying = false
    }

    private fun createPlayer(): MediaPlayer? {
        val created = runCatching {
            MediaPlayer().apply {
                // 不申请音频焦点。静音音轨没有任何声音需要被听见，
                // 申请焦点只会把用户正在听的音乐打断——那才是真正影响使用的事。
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                context.resources.openRawResourceFd(R.raw.silence).use { fd ->
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
                isLooping = true
                prepare()
            }
        }.getOrNull()
        player = created
        return created
    }

    private companion object {
        const val SESSION_TAG = "PomodoroTimer"
        const val ARTWORK_SIZE_PX = 1024
        const val GRADIENT_DARKEN = 0.32f
    }
}
