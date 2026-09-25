package com.fysly.pomodoro.timer

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.fysly.pomodoro.data.AppSettings

/**
 * 阶段结束时的声音与震动。
 *
 * 通知渠道刻意保持静音，提醒方式全部在这里按用户设置控制，
 * 这样"关掉声音"就是真的关掉，而不是被系统渠道覆盖。
 */
class Alerts(private val context: Context) {

    // 持有引用，避免铃声在播放途中被回收
    private var ringtone: Ringtone? = null

    fun play(settings: AppSettings) {
        if (settings.soundEnabled) playSound()
        if (settings.vibrationEnabled) vibrate()
    }

    fun stop() {
        ringtone?.let { if (it.isPlaying) it.stop() }
        ringtone = null
    }

    private fun playSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: return
        runCatching {
            ringtone?.stop()
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        }
    }

    private fun vibrate() {
        val vibrator = resolveVibrator() ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0L, 300L, 150L, 300L), -1),
            )
        }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
