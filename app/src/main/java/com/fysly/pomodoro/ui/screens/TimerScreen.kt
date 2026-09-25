package com.fysly.pomodoro.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fysly.pomodoro.data.TaskItem
import com.fysly.pomodoro.domain.PomodoroPhase
import com.fysly.pomodoro.domain.TimerState
import com.fysly.pomodoro.domain.label
import com.fysly.pomodoro.ui.PomodoroViewModel
import com.fysly.pomodoro.ui.theme.TimerDigitsStyle
import com.fysly.pomodoro.ui.theme.phaseAccent
import com.fysly.pomodoro.util.formatClock

@Composable
fun TimerScreen(viewModel: PomodoroViewModel) {
    val state by viewModel.timerState.collectAsStateWithLifecycle()
    val task by viewModel.activeTask.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val allTasks by viewModel.tasks.collectAsStateWithLifecycle()

    var showTaskPicker by remember { mutableStateOf(false) }

    val accent = phaseAccent(state.phase)
    val track = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CycleDots(
                total = settings.cyclesBeforeLongBreak,
                completed = completedInSet(state, settings.cyclesBeforeLongBreak),
                accent = accent,
                track = track,
                phase = state.phase,
            )
            IconButton(onClick = viewModel::resetAll) {
                Icon(
                    Icons.Filled.RestartAlt,
                    contentDescription = "重来一组",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        PhaseChip(state.phase, accent)

        Spacer(Modifier.weight(1f))

        TimerRing(
            progress = state.progress,
            accent = accent,
            track = track,
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .aspectRatio(1f),
        ) {
            Text(
                text = formatClock(state.remainingSeconds),
                style = TimerDigitsStyle.copy(
                    fontSize = digitsSizeFor(state.remainingSeconds),
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (state.isRunning) "进行中" else "已暂停",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        TaskCard(
            task = task,
            phase = state.phase,
            onClick = { showTaskPicker = true },
        )

        Spacer(Modifier.height(20.dp))

        Controls(
            isRunning = state.isRunning,
            accent = accent,
            onToggle = viewModel::toggleTimer,
            onReset = viewModel::resetTimer,
            onSkip = viewModel::skipPhase,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "今日 ${stats.todayCount} / ${settings.dailyGoal} 个番茄",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
    }

    if (showTaskPicker) {
        TaskPickerDialog(
            tasks = allTasks,
            activeTaskId = task?.id,
            onSelect = {
                viewModel.selectTask(it)
                showTaskPicker = false
            },
            onClear = {
                viewModel.clearActiveTask()
                showTaskPicker = false
            },
            onDismiss = { showTaskPicker = false },
        )
    }
}

/** 当前这一组里已完成的专注数；长休息时整排点亮。 */
private fun completedInSet(state: TimerState, cycles: Int): Int = when {
    state.phase == PomodoroPhase.LONG_BREAK -> cycles
    else -> state.completedFocusInSet % cycles.coerceAtLeast(1)
}

private fun digitsSizeFor(remainingSeconds: Int) = if (remainingSeconds >= 3600) 52.sp else 64.sp

@Composable
private fun CycleDots(
    total: Int,
    completed: Int,
    accent: Color,
    track: Color,
    phase: PomodoroPhase,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { index ->
            val done = index < completed
            val isCurrent = index == completed && phase == PomodoroPhase.FOCUS
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(if (isCurrent) 12.dp else 9.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            done -> accent
                            isCurrent -> accent.copy(alpha = 0.45f)
                            else -> track
                        },
                    ),
            )
        }
    }
}

@Composable
private fun PhaseChip(phase: PomodoroPhase, accent: Color) {
    val animated by animateColorAsState(accent, label = "phaseAccent")
    Surface(
        shape = RoundedCornerShape(50),
        color = animated.copy(alpha = 0.14f),
    ) {
        Text(
            text = phase.label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = animated,
        )
    }
}

@Composable
private fun TimerRing(
    progress: Float,
    accent: Color,
    track: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 这里刻意**不**写成 `by animateFloatAsState(...)`。
    //
    // 用 `by` 会在组合阶段读取动画值，于是动画每推进一帧就要重新执行一次组合：
    // 进度每秒变一次、动画 400ms，等于每秒有二十多帧在重组整个表盘。
    // 保持成 State、把 .value 的读取放进 Canvas 的绘制 lambda 里，
    // 动画就只驱动绘制阶段，组合阶段完全不受影响。
    // 这是 Compose 性能准则里"把状态读取推迟到尽可能低的阶段"的典型场景。
    val progressState = animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400, easing = LinearEasing),
        label = "timerProgress",
    )
    val accentState = animateColorAsState(accent, label = "ringAccent")

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val animatedProgress = progressState.value
            val animatedAccent = accentState.value

            val strokeWidth = 14.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)

            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            if (animatedProgress > 0f) {
                drawArc(
                    color = animatedAccent,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
private fun TaskCard(
    task: TaskItem?,
    phase: PomodoroPhase,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task?.title ?: "未关联任务",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (task != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = task?.let { "已完成 ${it.completedPomodoros} / 预计 ${it.estimatedPomodoros} 个番茄" }
                        ?: "点这里选一个任务，专注会计入它",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (phase == PomodoroPhase.FOCUS && task != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${task.completedPomodoros}/${task.estimatedPomodoros}",
                    style = MaterialTheme.typography.labelLarge,
                    color = phaseAccent(phase),
                )
            }
        }
    }
}

@Composable
private fun Controls(
    isRunning: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onReset) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "重置本阶段",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FilledIconButton(
            onClick = onToggle,
            modifier = Modifier.size(76.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent),
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isRunning) "暂停" else "开始",
                modifier = Modifier.size(38.dp),
            )
        }

        IconButton(onClick = onSkip) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = "跳过本阶段",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskPickerDialog(
    tasks: List<TaskItem>,
    activeTaskId: String?,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择任务") },
        text = {
            if (tasks.isEmpty()) {
                Text("还没有任务，去「任务」页添加一个吧。")
            } else {
                Column {
                    tasks.filterNot { it.isDone }.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(item.id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (item.id == activeTaskId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                            )
                            Text(
                                text = "${item.completedPomodoros}/${item.estimatedPomodoros}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClear) { Text("不关联任务") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
