package com.fysly.pomodoro.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.fysly.pomodoro.ui.screens.SettingsScreen
import com.fysly.pomodoro.ui.screens.StatsScreen
import com.fysly.pomodoro.ui.screens.TasksScreen
import com.fysly.pomodoro.ui.screens.TimerScreen

private enum class Destination(
    val label: String,
    val icon: ImageVector,
) {
    Timer("计时", Icons.Filled.Timer),
    Tasks("任务", Icons.Filled.Checklist),
    Stats("统计", Icons.Filled.BarChart),
    Settings("设置", Icons.Filled.Settings),
}

/**
 * 点底栏切页的滑动时长。Navigation Compose 默认的淡入淡出是 700ms，
 * 切一次要等大半秒；这里给一个短而干脆的时长。
 */
private const val TAB_TRANSITION_MS = 260

/**
 * 四个页签的容器。
 *
 * 这里**没有**用 Navigation Compose。原因很直接：NavHost 的转场是
 * `AnimatedContent`，切换时会把新旧两个页面各自当成一棵独立子树去组合、测量、加裁剪层，
 * 而且目标页是在动画开始的那一刻才第一次组合的——一个稍重的页面组合十几毫秒，
 * 动画的第一帧就直接掉了。
 *
 * 换成 `HorizontalPager` 之后：
 * - 页面切换变成了 LazyLayout 的滚动，只改放置位置，是开销最小的一类动画；
 * - `beyondViewportPageCount = 1` 会让相邻页**提前组合好**，动画开始时目标页已经在了，
 *   第一帧不会因为现场组合而卡住；
 * - 顺带白拿了左右滑动切页的手势。
 *
 * 页面本身的滚动位置由 LazyLayout 的 saveable 机制保存，切走再切回来不会丢失。
 */
@Composable
fun PomodoroRoot(viewModel: PomodoroViewModel) {
    val pages = Destination.entries
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            PomodoroBottomBar(
                // 用 targetPage 而不是 currentPage。
                //
                // 从第 1 页跳到第 4 页时，pager 会依次滚过第 2、3 页，
                // currentPage 也就跟着 1→2→3→4 地变——底栏中间两个按钮会依次亮一下再灭，
                // 看起来就是"闪"。targetPage 在滚动一开始就指向终点，全程只变一次。
                selectedIndex = pagerState.targetPage,
                onSelect = { index ->
                    scope.launch {
                        pagerState.animateScrollToPage(
                            page = index,
                            animationSpec = tween(
                                durationMillis = TAB_TRANSITION_MS,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            // 让所有页面都保持已组合状态。
            //
            // 这里踩过一次坑：最初设成 1（只预组合相邻一页）。从「计时」直接跳到「设置」
            // 要跨三页，260ms 的滚动过程中后两页才第一次组合，组合落进了动画的关键路径，
            // 表现就是帧率上不去。设成页面总数之后，任何一次切换涉及的页面都已经组合好了，
            // 动画只剩下纯位移，这是开销最小的一类动画。
            //
            // 代价是四个页面始终处于组合状态、冷启动稍慢一点。四个页面都很轻，
            // 换来的稳定性是值得的。
            beyondViewportPageCount = pages.size,
            // 页面之间不留缝，也不要有回弹过冲，点一下就走完
            pageSpacing = 0.dp,
        ) { page ->
            when (pages[page]) {
                Destination.Timer -> TimerScreen(viewModel)
                Destination.Tasks -> TasksScreen(viewModel)
                Destination.Stats -> StatsScreen(viewModel)
                Destination.Settings -> SettingsScreen(viewModel)
            }
        }
    }
}

/**
 * 底栏。只接收"当前第几页"和"点了第几页"，不接触任何导航对象，
 * 这样它在页面滑动过程中只会因为页码变化而重组，不会每帧重组。
 */
@Composable
private fun PomodoroBottomBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    NavigationBar {
        Destination.entries.forEachIndexed { index, destination ->
            NavigationBarItem(
                selected = index == selectedIndex,
                onClick = { if (index != selectedIndex) onSelect(index) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
            )
        }
    }
}
