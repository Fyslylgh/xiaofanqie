package com.fysly.pomodoro.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fysly.pomodoro.data.DailyStat
import com.fysly.pomodoro.ui.PomodoroViewModel
import com.fysly.pomodoro.util.formatDuration
import com.fysly.pomodoro.util.formatDurationCompact
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun StatsScreen(viewModel: PomodoroViewModel) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    val accent = MaterialTheme.colorScheme.primary
    val todayProgress = if (settings.dailyGoal <= 0) {
        0f
    } else {
        (stats.todayCount.toFloat() / settings.dailyGoal).coerceIn(0f, 1f)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = "今日番茄",
                    value = stats.todayCount.toString(),
                    accent = accent,
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = "今日专注",
                    value = formatDurationCompact(stats.todaySeconds),
                    accent = accent,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = "累计番茄",
                    value = stats.totalCount.toString(),
                    accent = accent,
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = "连续达标",
                    value = "${stats.streakDays} 天",
                    accent = accent,
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("今日目标", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${stats.todayCount} / ${settings.dailyGoal}",
                            style = MaterialTheme.typography.labelLarge,
                            color = accent,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { todayProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("最近 7 天", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(16.dp))
                    WeeklyChart(
                        daily = stats.daily,
                        goal = settings.dailyGoal,
                        accent = accent,
                        goalLine = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("累计专注时长", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = formatDuration(stats.totalSeconds),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        val counted = tasks.filter { it.completedPomodoros > 0 }
        if (counted.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("任务投入", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        counted.sortedByDescending { it.completedPomodoros }.forEach { task ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Text(
                                    text = "${task.completedPomodoros} 个",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
    }
}

/** 手绘的柱状图，避免为了一个图表引入第三方依赖。 */
@Composable
private fun WeeklyChart(
    daily: List<DailyStat>,
    goal: Int,
    accent: androidx.compose.ui.graphics.Color,
    goalLine: androidx.compose.ui.graphics.Color,
) {
    if (daily.isEmpty()) return

    val today = remember { LocalDate.now() }
    val labels = remember(daily) {
        daily.map { stat ->
            val date = Instant.ofEpochMilli(stat.dayStart).atZone(ZoneId.systemDefault()).toLocalDate()
            date to weekdayLabel(date)
        }
    }

    val peak = maxOf(daily.maxOf { it.focusCount }, goal, 1)

    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            val slot = size.width / daily.size
            val barWidth = slot * 0.46f
            val radius = CornerRadius(barWidth / 2f, barWidth / 2f)

            daily.forEachIndexed { index, stat ->
                val ratio = stat.focusCount.toFloat() / peak
                val barHeight = if (stat.focusCount == 0) 3.dp.toPx() else size.height * ratio
                val left = slot * index + (slot - barWidth) / 2f
                val isToday = labels[index].first == today

                drawRoundRect(
                    color = if (isToday) accent else accent.copy(alpha = 0.35f),
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = radius,
                )
            }

            // 目标线
            if (goal > 0) {
                val goalY = size.height * (1f - goal.toFloat() / peak)
                drawLine(
                    color = goalLine,
                    start = Offset(0f, goalY),
                    end = Offset(size.width, goalY),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEach { (date, label) ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (date == today) {
                            accent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

private fun weekdayLabel(date: LocalDate): String = when (date.dayOfWeek.value) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    else -> "日"
}
