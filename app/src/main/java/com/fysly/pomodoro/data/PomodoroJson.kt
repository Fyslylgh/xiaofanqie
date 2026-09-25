package com.fysly.pomodoro.data

import kotlinx.serialization.json.Json

/**
 * 全局唯一的 JSON 配置。
 *
 * - [Json.ignoreUnknownKeys]：将来删掉某个字段时，旧数据仍然能解析，而不是整份设置丢失。
 * - [Json.encodeDefaults]：写入时带上默认值，数据自解释，出问题便于排查。
 */
val PomodoroJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
