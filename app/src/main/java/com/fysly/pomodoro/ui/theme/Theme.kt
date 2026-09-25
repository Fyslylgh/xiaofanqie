package com.fysly.pomodoro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.fysly.pomodoro.data.ThemeColor
import com.fysly.pomodoro.data.ThemeMode
import com.fysly.pomodoro.domain.PomodoroPhase

private val ShortBreakLight = Color(0xFF1F8A5B)
private val ShortBreakDark = Color(0xFF7ED9A8)
private val LongBreakLight = Color(0xFF2F6FB0)
private val LongBreakDark = Color(0xFF9CC7F0)

@Composable
fun PomodoroTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themeColor: ThemeColor = ThemeColor.TOMATO,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val p = paletteFor(themeColor, dark)
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.primary,
            onPrimary = p.onPrimary,
            primaryContainer = p.primaryContainer,
            onPrimaryContainer = p.onPrimaryContainer,
            secondary = p.secondary,
            secondaryContainer = p.secondaryContainer,
            tertiary = p.tertiary,
        )
    } else {
        lightColorScheme(
            primary = p.primary,
            onPrimary = p.onPrimary,
            primaryContainer = p.primaryContainer,
            onPrimaryContainer = p.onPrimaryContainer,
            secondary = p.secondary,
            secondaryContainer = p.secondaryContainer,
            tertiary = p.tertiary,
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = PomodoroTypography,
        content = content,
    )
}

/** 当前是否为深色主题。 */
val isDarkTheme: Boolean
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

private fun Color.luminance(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

/** 三个阶段各自的强调色：专注跟随主题色，休息用固定的绿/蓝以便一眼区分。 */
@Composable
@ReadOnlyComposable
fun phaseAccent(phase: PomodoroPhase): Color = when (phase) {
    PomodoroPhase.FOCUS -> MaterialTheme.colorScheme.primary
    PomodoroPhase.SHORT_BREAK -> if (isDarkTheme) ShortBreakDark else ShortBreakLight
    PomodoroPhase.LONG_BREAK -> if (isDarkTheme) LongBreakDark else LongBreakLight
}

/** 阶段强调色对应的容器色，用于浅底卡片。 */
@Composable
@ReadOnlyComposable
fun phaseAccentContainer(phase: PomodoroPhase): Color = when (phase) {
    PomodoroPhase.FOCUS -> MaterialTheme.colorScheme.primaryContainer
    PomodoroPhase.SHORT_BREAK -> MaterialTheme.colorScheme.secondaryContainer
    PomodoroPhase.LONG_BREAK -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
}
