package com.fysly.pomodoro.data

import com.fysly.pomodoro.domain.PomodoroPhase
import com.fysly.pomodoro.domain.TimerState
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持久化模型与序列化器是 R8 混淆最容易打坏的地方（发布包会重命名类），
 * 所以这里把每种落盘结构都做一次往返验证。
 */
class SerializationTest {

    private val json = PomodoroJson

    @Test
    fun `设置往返一致`() {
        val original = AppSettings(
            focusMinutes = 45,
            shortBreakMinutes = 8,
            longBreakMinutes = 20,
            cyclesBeforeLongBreak = 3,
            autoStartBreaks = false,
            autoStartFocus = true,
            soundEnabled = false,
            vibrationEnabled = true,
            keepScreenOn = true,
            themeMode = ThemeMode.DARK,
            themeColor = ThemeColor.OCEAN,
            dailyGoal = 12,
        )

        val encoded = json.encodeToString(AppSettings.serializer(), original)
        val decoded = json.decodeFromString(AppSettings.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `任务列表往返一致`() {
        val serializer = ListSerializer(TaskItem.serializer())
        val original = listOf(
            TaskItem(id = "a", title = "写方案", estimatedPomodoros = 3, completedPomodoros = 1),
            TaskItem(id = "b", title = "改 bug", estimatedPomodoros = 2, isDone = true),
        )

        val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, original))

        assertEquals(original, decoded)
    }

    @Test
    fun `专注记录列表往返一致`() {
        val serializer = ListSerializer(SessionRecord.serializer())
        val original = listOf(
            SessionRecord(endedAt = 1_700_000_000_000, durationSeconds = 1500, taskTitle = "写方案"),
            SessionRecord(endedAt = 1_700_000_900_000, durationSeconds = 1500, taskTitle = null),
        )

        val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, original))

        assertEquals(original, decoded)
    }

    @Test
    fun `计时器快照往返一致`() {
        val original = TimerState(
            phase = PomodoroPhase.LONG_BREAK,
            remainingSeconds = 420,
            totalSeconds = 900,
            isRunning = true,
            completedFocusInSet = 4,
            deadlineElapsedRealtime = 123_456_789L,
        )

        val decoded = json.decodeFromString(
            TimerState.serializer(),
            json.encodeToString(TimerState.serializer(), original),
        )

        assertEquals(original, decoded)
    }

    @Test
    fun `多出未知字段时旧数据仍可解析`() {
        // 模拟"降级安装"：数据里带着当前版本不认识的字段
        val raw = """{"focusMinutes":30,"shortBreakMinutes":6,"longBreakMinutes":18,
            "cyclesBeforeLongBreak":4,"autoStartBreaks":true,"autoStartFocus":false,
            "soundEnabled":true,"vibrationEnabled":true,"keepScreenOn":false,
            "themeMode":"SYSTEM","themeColor":"TOMATO","dailyGoal":8,
            "futureFieldFromNewerVersion":"whatever"}"""

        val decoded = json.decodeFromString(AppSettings.serializer(), raw)

        assertEquals(30, decoded.focusMinutes)
        assertEquals(ThemeColor.TOMATO, decoded.themeColor)
    }

    @Test
    fun `缺字段时用默认值补齐`() {
        val raw = """{"focusMinutes":50}"""

        val decoded = json.decodeFromString(AppSettings.serializer(), raw)

        assertEquals(50, decoded.focusMinutes)
        assertEquals(TimerConfigDefaults.SHORT_BREAK, decoded.shortBreakMinutes)
        assertEquals(ThemeMode.SYSTEM, decoded.themeMode)
    }

    @Test
    fun `坏数据解析失败而不是静默返回半截内容`() {
        // 仓储层会捕获异常并回退默认值，这里确认异常确实会被抛出
        var threw = false
        try {
            json.decodeFromString(AppSettings.serializer(), "{ 这不是 JSON")
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `枚举按名字序列化`() {
        val encoded = json.encodeToString(
            ListSerializer(ThemeColor.serializer()),
            listOf(ThemeColor.GRAPE, ThemeColor.AMBER),
        )

        assertTrue(encoded.contains("GRAPE"))
        assertTrue(encoded.contains("AMBER"))
    }

    @Test
    fun `可空任务标题不会被写成字符串 null`() {
        val encoded = json.encodeToString(
            SessionRecord.serializer(),
            SessionRecord(endedAt = 1L, durationSeconds = 60, taskTitle = null),
        )

        assertTrue(encoded.contains("\"taskTitle\":null"))
    }

    @Test
    fun `空列表往返仍是空列表`() {
        val serializer = ListSerializer(TaskItem.serializer())
        val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, emptyList()))

        assertNull(decoded.firstOrNull())
        assertEquals(0, decoded.size)
    }

    /** 只用于测试里引用默认值，避免把 5 这种魔法数字散在断言中。 */
    private object TimerConfigDefaults {
        const val SHORT_BREAK = 5
    }
}
