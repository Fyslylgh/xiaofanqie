package com.fysly.pomodoro.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 读取当前安装包的版本名。
 *
 * 界面上要显示版本号时一律走这里，不要写死字符串——写死的话每次改
 * `build.gradle.kts` 里的 `versionName`，界面上的数字都不会跟着变。
 *
 * 读 PackageManager 还顺带解决一件事：debug 包因为 `versionNameSuffix = "-debug"`，
 * 会显示成 `1.1.0-debug`，一眼能看出装的是哪个包。
 */
fun appVersionName(context: Context): String = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    info.versionName.orEmpty()
}.getOrDefault("")
