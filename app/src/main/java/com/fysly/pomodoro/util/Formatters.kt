package com.fysly.pomodoro.util

/** 把秒数格式化成 mm:ss；超过一小时用 h:mm:ss。 */
fun formatClock(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** 把秒数说成人话，例如 "1 小时 25 分"、"48 分钟"。 */
fun formatDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
        hours > 0 -> "$hours 小时"
        minutes > 0 -> "$minutes 分钟"
        else -> "$safe 秒"
    }
}

/** 粗略时长，用于统计卡片这种空间有限的地方。 */
fun formatDurationCompact(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    return when {
        hours > 0 -> "${hours}h${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${safe}s"
    }
}
