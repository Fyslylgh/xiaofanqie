package com.fysly.pomodoro.ui.theme

import androidx.compose.ui.graphics.Color
import com.fysly.pomodoro.data.ThemeColor

/**
 * 每个主题色给出一组 Material3 需要的关键色。
 * 剩下的容器色、表面色交给 Material3 的默认基线补齐。
 */
data class ThemePalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val tertiary: Color,
)

private fun palette(
    primary: Long,
    onPrimary: Long,
    primaryContainer: Long,
    onPrimaryContainer: Long,
    secondary: Long,
    secondaryContainer: Long,
    tertiary: Long,
) = ThemePalette(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    secondary = Color(secondary),
    secondaryContainer = Color(secondaryContainer),
    tertiary = Color(tertiary),
)

private val TomatoLight = palette(
    primary = 0xFFB3261E, onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFFFDAD5, onPrimaryContainer = 0xFF410E0B,
    secondary = 0xFF775652, secondaryContainer = 0xFFFFDAD5, tertiary = 0xFF6C5D2F,
)

private val TomatoDark = palette(
    primary = 0xFFFFB4AB, onPrimary = 0xFF690005,
    primaryContainer = 0xFF93000A, onPrimaryContainer = 0xFFFFDAD6,
    secondary = 0xFFE7BDB8, secondaryContainer = 0xFF5D3F3C, tertiary = 0xFFD8C58D,
)

private val ForestLight = palette(
    primary = 0xFF2E6A3E, onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFB2F1BE, onPrimaryContainer = 0xFF00210C,
    secondary = 0xFF52634F, secondaryContainer = 0xFFD5E8D0, tertiary = 0xFF39656B,
)

private val ForestDark = palette(
    primary = 0xFF97D5A3, onPrimary = 0xFF00391A,
    primaryContainer = 0xFF15522B, onPrimaryContainer = 0xFFB2F1BE,
    secondary = 0xFFB9CCB4, secondaryContainer = 0xFF3B4B38, tertiary = 0xFFA1CDD4,
)

private val OceanLight = palette(
    primary = 0xFF00639B, onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFCBE6FF, onPrimaryContainer = 0xFF001E31,
    secondary = 0xFF50606E, secondaryContainer = 0xFFD3E5F5, tertiary = 0xFF65587B,
)

private val OceanDark = palette(
    primary = 0xFF95CCFF, onPrimary = 0xFF003353,
    primaryContainer = 0xFF004A76, onPrimaryContainer = 0xFFCBE6FF,
    secondary = 0xFFB7C9D9, secondaryContainer = 0xFF384955, tertiary = 0xFFCFBFE6,
)

private val GrapeLight = palette(
    primary = 0xFF7B4E9B, onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFF1D9FF, onPrimaryContainer = 0xFF2C0044,
    secondary = 0xFF67596D, secondaryContainer = 0xFFEFDCF4, tertiary = 0xFF815252,
)

private val GrapeDark = palette(
    primary = 0xFFDDBBFF, onPrimary = 0xFF471367,
    primaryContainer = 0xFF5F2B7F, onPrimaryContainer = 0xFFF1D9FF,
    secondary = 0xFFD3C1D8, secondaryContainer = 0xFF4F4255, tertiary = 0xFFF4B7B6,
)

private val AmberLight = palette(
    primary = 0xFF8B5000, onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFFFDCBE, onPrimaryContainer = 0xFF2C1600,
    secondary = 0xFF725A42, secondaryContainer = 0xFFFEDDBD, tertiary = 0xFF58633A,
)

private val AmberDark = palette(
    primary = 0xFFFFB870, onPrimary = 0xFF4A2800,
    primaryContainer = 0xFF6A3C00, onPrimaryContainer = 0xFFFFDCBE,
    secondary = 0xFFE1C1A4, secondaryContainer = 0xFF59422D, tertiary = 0xFFC0CB9C,
)

private val SlateLight = palette(
    primary = 0xFF44617B, onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFCEE5FF, onPrimaryContainer = 0xFF001D34,
    secondary = 0xFF53606C, secondaryContainer = 0xFFD7E4F1, tertiary = 0xFF6B5778,
)

private val SlateDark = palette(
    primary = 0xFFACCAE8, onPrimary = 0xFF12324A,
    primaryContainer = 0xFF2B4962, onPrimaryContainer = 0xFFCEE5FF,
    secondary = 0xFFBBC8D6, secondaryContainer = 0xFF3B4854, tertiary = 0xFFD6BFE3,
)

fun paletteFor(themeColor: ThemeColor, dark: Boolean): ThemePalette = when (themeColor) {
    ThemeColor.TOMATO -> if (dark) TomatoDark else TomatoLight
    ThemeColor.FOREST -> if (dark) ForestDark else ForestLight
    ThemeColor.OCEAN -> if (dark) OceanDark else OceanLight
    ThemeColor.GRAPE -> if (dark) GrapeDark else GrapeLight
    ThemeColor.AMBER -> if (dark) AmberDark else AmberLight
    ThemeColor.SLATE -> if (dark) SlateDark else SlateLight
}

/** 主题色的中文名，设置页用。 */
val ThemeColor.label: String
    get() = when (this) {
        ThemeColor.TOMATO -> "番茄红"
        ThemeColor.FOREST -> "森林绿"
        ThemeColor.OCEAN -> "海洋蓝"
        ThemeColor.GRAPE -> "葡萄紫"
        ThemeColor.AMBER -> "琥珀橙"
        ThemeColor.SLATE -> "石板灰"
    }

/** 预览用的主题色样本。 */
val ThemeColor.previewSwatch: Color
    get() = when (this) {
        ThemeColor.TOMATO -> Color(0xFFD84315)
        ThemeColor.FOREST -> Color(0xFF2E7D32)
        ThemeColor.OCEAN -> Color(0xFF0277BD)
        ThemeColor.GRAPE -> Color(0xFF7B4E9B)
        ThemeColor.AMBER -> Color(0xFFF57F17)
        ThemeColor.SLATE -> Color(0xFF455A64)
    }
