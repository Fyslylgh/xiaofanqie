package com.fysly.pomodoro.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fysly.pomodoro.data.AppSettings
import com.fysly.pomodoro.data.MediaArtwork
import com.fysly.pomodoro.data.ThemeColor
import com.fysly.pomodoro.data.ThemeMode
import com.fysly.pomodoro.domain.TimerConfig
import com.fysly.pomodoro.ui.PomodoroViewModel
import com.fysly.pomodoro.ui.theme.label
import com.fysly.pomodoro.ui.theme.previewSwatch
import com.fysly.pomodoro.util.appVersionName
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: PomodoroViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hasCustomArtwork by viewModel.hasCustomArtwork.collectAsStateWithLifecycle()
    var showClearConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsSection("计时") {
                StepperRow(
                    title = "专注时长",
                    subtitle = "一个番茄有多长",
                    value = settings.focusMinutes,
                    suffix = "分钟",
                    range = TimerConfig.MIN_MINUTES..TimerConfig.MAX_FOCUS_MINUTES,
                    onChange = viewModel::setFocusMinutes,
                )
                StepperRow(
                    title = "短休息",
                    subtitle = "每个番茄之后",
                    value = settings.shortBreakMinutes,
                    suffix = "分钟",
                    range = TimerConfig.MIN_MINUTES..TimerConfig.MAX_MINUTES,
                    onChange = viewModel::setShortBreakMinutes,
                )
                StepperRow(
                    title = "长休息",
                    subtitle = "完成一整组之后",
                    value = settings.longBreakMinutes,
                    suffix = "分钟",
                    range = TimerConfig.MIN_MINUTES..TimerConfig.MAX_MINUTES,
                    onChange = viewModel::setLongBreakMinutes,
                )
                StepperRow(
                    title = "每组长休息前",
                    subtitle = "几个番茄算一组",
                    value = settings.cyclesBeforeLongBreak,
                    suffix = "个",
                    range = TimerConfig.MIN_CYCLES..TimerConfig.MAX_CYCLES,
                    onChange = viewModel::setCyclesBeforeLongBreak,
                )
            }
        }

        item {
            SettingsSection("自动衔接") {
                SwitchRow(
                    title = "自动开始休息",
                    subtitle = "专注结束后立刻开始休息计时",
                    checked = settings.autoStartBreaks,
                    onChange = viewModel::setAutoStartBreaks,
                )
                SwitchRow(
                    title = "自动开始专注",
                    subtitle = "休息结束后立刻开始下一个番茄",
                    checked = settings.autoStartFocus,
                    onChange = viewModel::setAutoStartFocus,
                )
            }
        }

        item {
            SettingsSection("提醒") {
                SwitchRow(
                    title = "结束响铃",
                    subtitle = "阶段走完时播放提示音",
                    checked = settings.soundEnabled,
                    onChange = viewModel::setSoundEnabled,
                )
                SwitchRow(
                    title = "结束震动",
                    subtitle = "阶段走完时震动提醒",
                    checked = settings.vibrationEnabled,
                    onChange = viewModel::setVibrationEnabled,
                )
                SwitchRow(
                    title = "计时中保持屏幕常亮",
                    subtitle = "仅在本应用前台且正在计时时生效",
                    checked = settings.keepScreenOn,
                    onChange = viewModel::setKeepScreenOn,
                )
            }
        }

        item {
            SettingsSection("通知") {
                SwitchRow(
                    title = "媒体卡片模式（实验性）",
                    subtitle = "把倒计时显示在锁屏播放器和音乐流体云里（播放静音音轨）",
                    checked = settings.mediaCardEnabled,
                    onChange = viewModel::setMediaCardEnabled,
                )
                if (settings.mediaCardEnabled) {
                    HintRow(
                        "ColorOS 17 / Flyme 用户建议在系统设置里打开「多应用同时输出音频」，" +
                            "否则计时可能顶掉正在播放的音乐。",
                    )
                    MediaArtworkPicker(
                        viewModel = viewModel,
                        settings = settings,
                        hasCustom = hasCustomArtwork,
                    )
                }
            }
        }

        item {
            SettingsSection("外观") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                }

                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("主题色", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ThemeColor.entries.forEach { color ->
                            val selected = settings.themeColor == color
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(color.previewSwatch)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        shape = CircleShape,
                                    )
                                    .clickable { viewModel.setThemeColor(color) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = settings.themeColor.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SettingsSection("目标与数据") {
                StepperRow(
                    title = "每日目标",
                    subtitle = "统计页据此计算达标与连续天数",
                    value = settings.dailyGoal,
                    suffix = "个",
                    range = 1..24,
                    onChange = viewModel::setDailyGoal,
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "清除专注记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showClearConfirm = true }
                            .padding(vertical = 6.dp),
                    )
                    Text(
                        text = "只清空统计历史，任务列表不受影响",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SettingsSection("关于") {
                Column(Modifier.padding(16.dp)) {
                    val appContext = LocalContext.current
                    val version = remember(appContext) { appVersionName(appContext) }
                    Text(
                        text = "小番茄 v$version",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "计时基于截止时刻计算，切到后台或锁屏都不会走偏；" +
                            "计时期间由前台服务保证不被系统回收。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清除专注记录？") },
            text = { Text("所有历史番茄数据会被删除，且无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearConfirm = false
                    },
                ) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
    }

private val MediaArtwork.label: String
    get() = when (this) {
        // 标签刻意只用两个字：三个 chip 并排，"自定义图片" 会折成两行，
        // 比另外两个高出一截，看起来就是没对齐。含义交给上方的分组标题去交代。
        MediaArtwork.GRADIENT -> "渐变"
        MediaArtwork.APP_ICON -> "图标"
        MediaArtwork.CUSTOM -> "图片"
    }

/**
 * 媒体卡片封面的选择。
 *
 * 单独抽出来是因为它比其它设置项复杂：要拉起系统相册，还要知道导入是否成功——
 * 解码失败、图片损坏这些情况用户得知道，否则会以为换了图却没生效。
 *
 * 用 `PickVisualMedia` 而不是旧的 `GET_CONTENT`：Android 13+ 上走系统照片选择器，
 * 不需要申请任何读相册权限，低版本自动回落到文档选择器。
 */
@Composable
private fun MediaArtworkPicker(
    viewModel: PomodoroViewModel,
    settings: AppSettings,
    hasCustom: Boolean,
) {
    val scope = rememberCoroutineScope()
    var importFailed by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch { importFailed = !viewModel.importCustomArtwork(uri) }
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("封面", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaArtwork.entries.forEach { mode ->
                FilterChip(
                    selected = settings.mediaArtwork == mode,
                    onClick = {
                        importFailed = false
                        viewModel.setMediaArtwork(mode)
                    },
                    // 强制单行，避免某个标签折行后把整排撑得高低不一
                    label = { Text(mode.label, maxLines = 1) },
                )
            }
        }

        if (settings.mediaArtwork == MediaArtwork.CUSTOM) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        importFailed = false
                        pickImage.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                ) { Text(if (hasCustom) "更换图片" else "选择图片") }

                if (hasCustom) {
                    TextButton(onClick = viewModel::clearCustomArtwork) { Text("清除") }
                }
            }
            Text(
                text = if (hasCustom) "已设置" else "还没选图片，暂用渐变",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (importFailed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "这张图片读取失败，换一张试试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 设置项下面的一句补充说明，比 SwitchRow 的副标题更次要。 */
@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    value: Int,
    suffix: String,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { onChange((value - 1).coerceAtLeast(range.first)) },
            enabled = value > range.first,
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "减少")
        }
        Text(
            text = "$value $suffix",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(72.dp),
        )
        IconButton(
            onClick = { onChange((value + 1).coerceAtMost(range.last)) },
            enabled = value < range.last,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "增加")
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
