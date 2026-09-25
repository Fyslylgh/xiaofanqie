package com.fysly.pomodoro.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val PomodoroTypography = Typography()

/** 计时器大数字：等宽字体，秒数跳动时不会左右晃。 */
val TimerDigitsStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Light,
    fontSize = 72.sp,
    letterSpacing = (-2).sp,
)

/** 通知里用的紧凑样式。 */
val MonoLabelStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
)
