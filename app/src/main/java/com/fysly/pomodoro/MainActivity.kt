package com.fysly.pomodoro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fysly.pomodoro.ui.PomodoroRoot
import com.fysly.pomodoro.ui.PomodoroViewModel
import com.fysly.pomodoro.ui.theme.PomodoroTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val viewModel: PomodoroViewModel =
                viewModel(factory = PomodoroViewModel.Factory)

            val settings by viewModel.settings.collectAsStateWithLifecycle()
            // 只订阅这一个 Boolean，不订阅整个计时状态。
            // 剩余秒数每秒都在变，订阅它会让整个 setContent 每秒重组一次。
            val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()

            KeepScreenWhileRunning(enabled = keepScreenOn)
            RequestNotificationPermission()

            PomodoroTheme(
                themeMode = settings.themeMode,
                themeColor = settings.themeColor,
            ) {
                PomodoroRoot(viewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 切后台时把计时快照落盘，进程被回收后还能准确恢复
        (application as PomodoroApplication).timerController.persistNow()
    }
}

@Composable
private fun KeepScreenWhileRunning(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/** Android 13 起通知需要运行时权限；没授权时计时照常，只是看不到通知。 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也继续可用 */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
