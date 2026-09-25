# 小番茄

一个原生 Android 番茄钟，Kotlin + Jetpack Compose 写的，没有第三方 UI 或图表依赖。

## 下载

**⬇ [app-release.apk](https://github.com/Fyslylgh/xiaofanqie/releases/latest)** · 1.4 MB · Android 8.0 及以上

用工程内的调试密钥签名，传到手机上直接安装即可。历史版本见 [Releases](https://github.com/Fyslylgh/xiaofanqie/releases)。

## 功能

**计时**
- 专注 / 短休息 / 长休息 三阶段状态机，每完成 N 个专注进入长休息（N 可配）
- 自定义三种时长、每组长休息前的专注数
- 自动衔接：可分别设置「专注结束自动开始休息」和「休息结束自动开始专注」
- 开始 / 暂停 / 重置本阶段 / 跳过本阶段 / 重来一组
- 计时基于**截止时刻**而非逐秒累加，切后台、锁屏、甚至进程被回收后回到前台，剩余时间都准确

**后台与提醒**
- 前台服务保证计时进程不被回收
- 阶段结束响铃 + 震动，两个开关独立
- 可选计时中保持屏幕常亮

**任务**
- 任务清单，每个任务设定预计番茄数
- 选中任务后，完成的专注会自动计入该任务
- 进度条、完成勾选、编辑、删除

**统计**
- 今日番茄数 / 今日专注时长 / 累计番茄 / 连续达标天数
- 最近 7 天柱状图（自绘，含每日目标虚线）
- 今日目标进度、累计专注时长、各任务的番茄投入

**外观**
- 启动图标：白底黑色极简图形，只有一个缺口圆环加圆心
- 6 种主题色：番茄红、森林绿、海洋蓝、葡萄紫、琥珀橙、石板灰
- 深色模式：跟随系统 / 强制浅色 / 强制深色
- 页签之间横向滑动切换（260ms），也支持手指左右滑；底层是 Compose 的 `HorizontalPager`

**通知**
- 常驻通知只用系统原生元素：小图标、标题、正文、细进度条、暂停与跳过按钮。
  不套自定义样式、不染背景色，交给各家系统按自己的模板去画
- 倒计时交给系统 chronometer，不靠每秒重绘文字；进度条则靠重新下发通知推进
- 阶段结束另发一条高优先级提醒通知（渠道静音，声音和震动由应用按设置自己控制）
- **媒体卡片模式**（实验性，**默认关闭**）：把倒计时伪装成一个正在播放的媒体会话，
  让它出现在锁屏播放器和 OPPO 的音乐流体云里。封面可选主题色渐变 / 应用图标 / 相册里的图片，
  详见下面「媒体卡片模式」一节
- **控制中心磁贴**：把「开始专注」加到快捷设置里，点一下开始计时、再点一下暂停，
  磁贴上直接显示当前阶段和剩余时间
- 标准 Android 实时活动那条路走不通，原因和完整调研记录见「关于流体云」一节

## 工程结构

```
app/src/main/java/com/fysly/pomodoro/
├── MainActivity.kt              入口，edge-to-edge、通知权限、屏幕常亮
├── PomodoroApplication.kt       持有应用级 TimerController
├── domain/                      纯 Kotlin，无 Android 依赖，可直接单测
│   ├── PomodoroPhase.kt         三个阶段
│   ├── TimerConfig.kt           时长等配置，带区间收敛
│   ├── TimerState.kt            状态快照（含截止时刻）
│   └── PomodoroEngine.kt        状态机：start/pause/reset/skip/advance
├── data/
│   ├── Models.kt                任务、专注记录、设置
│   ├── PomodoroJson.kt          全局 JSON 配置
│   ├── PomodoroRepository.kt    DataStore + JSON 持久化
│   ├── CustomArtworkStore.kt    媒体卡片自定义封面的导入与读取
│   ├── StatsCalculator.kt       记录 → 统计汇总（纯函数）
│   └── SettingsMapper.kt        设置 → 计时参数
├── timer/
│   ├── TimerController.kt       应用级计时中枢，界面与服务共用
│   ├── TimerService.kt          前台服务与常驻通知
│   ├── TimerNotifications.kt    通知渠道与通知构建
│   ├── NotificationAccent.kt    通知用的 ARGB 强调色
│   ├── FluidCloudInfo.kt        流体云支持情况的说明文字
│   ├── MediaSessionController.kt 媒体卡片模式：把计时伪装成正在播放的媒体会话
│   └── Alerts.kt                铃声与震动
├── tile/
│   └── FocusTileService.kt      控制中心磁贴：一点开始专注 / 暂停
├── ui/
│   ├── PomodoroRoot.kt          底部导航 + HorizontalPager 页签容器
│   ├── PomodoroViewModel.kt     界面状态
│   ├── theme/                   配色、排版、主题色
│   └── screens/                 计时 / 任务 / 统计 / 设置
└── util/Formatters.kt           时间格式化
```

## 关于流体云（未接入）

**结论：本应用没有接入 OPPO 的流体云。** 这一节把完整的调研过程记录下来，将来若要重做不必重走弯路。

### 试过的路子：Android 16 的实时活动 API

ColorOS 16 发布时官方说法是流体云**完整接入了 Android 16 的 Live Updates API**，
应用只要遵循 Google 的实时活动规范就能直接适配，不需要 OPPO 私有 SDK。
按这个说法实现了一版，并读 AOSP 源码（`android16-release` 分支）确认了系统的判定条件：

```java
// NotificationManagerService.enqueueNotificationInternal()
if (mPreferencesHelper.canBePromoted(pkg, notificationUid)      // ① 按应用的用户开关
        && notification.hasPromotableCharacteristics()          // ② 通知自身条件
        && channel.getImportance() > IMPORTANCE_MIN) {          // ③ 渠道重要性
    notification.flags |= FLAG_PROMOTED_ONGOING;
}
```

```java
// Notification.hasPromotableCharacteristics()
if (!isOngoingEvent() || isGroupSummary() || containsCustomViews() || !hasTitle()) return false;
if (isOngoingCallStyle()) return true;
return isColorizedRequested() && hasPromotableStyle();   // ← colorized 是硬性条件
```

三点逐一对照，应用侧全部满足：

| 条件 | 结果 |
| --- | --- |
| ① `canBePromoted` | `PreferencesHelper.DEFAULT_CAN_HAVE_PROMOTED_NOTIFS = true`，默认就是开的 |
| ② `hasPromotableCharacteristics()` | 常驻、非群组摘要、有标题、`setColorized(true)`、`ProgressStyle` 全部到位 |
| ③ `importance > IMPORTANCE_MIN` | 渠道为 `IMPORTANCE_LOW`，满足 |

**但系统始终没有给它 `FLAG_PROMOTED_ONGOING`。** 实测机型是一加 PJZ110、Android 17（API 37）。
为了定位，应用内临时做过一个排查面板：读回通知的 `flags` 和 `extras`，把六项判定条件在设备上重新算一遍，
结果显示全部为真，系统依旧不提升。结论明确之后这个面板已经撤掉了。

顺带排掉的几个嫌疑（都查过源码，不是猜测）：

- androidx 没把 `setColorized` 传下去 —— `NotificationCompatBuilder` 里有 `if (b.mColorizedSet) Api26Impl.setColorized(...)`，传了
- `ProgressStyle` 在 API 37 上不生效 —— `apply()` 的判断是 `SDK_INT >= 36`，没有上界
- Android 17 新增了实时活动权限 —— `Manifest.permission` 里没有相关新权限

还要记一个坑：**`setColorized(true)` 是提升的硬性条件，但它会把 `setColor()` 的颜色铺满整个通知背景。**
为了满足条件加它之后，通知就变成了一张大色块卡片。`USE_COLORIZED_NOTIFICATIONS` 是签名级权限，
理论上普通应用拿不到 `FLAG_CAN_COLORIZE`、不该真的染色，但在 ColorOS 上确实染了。

### 真正的原因：第三方入口是「潘塔纳尔泛在服务」

OPPO 开放平台的文档写明，流体云的第三方入口是**泛在服务**：

|  | 标准 Android 通知（本项目） | 泛在服务（OPPO 的路线） |
| --- | --- | --- |
| 形态 | Android 通知 | 独立的服务软件包 |
| 语言 | Kotlin | **JS + CSS + OML 模板** |
| 产物 | `.apk` | **`.upk` 二进制包** |
| 运行管理 | Android 通知系统 | 服务治理框架（UMS） |
| 开发工具 | Gradle / Android Studio | **潘塔纳尔开发者IDE**（OPPO 专有） |
| 分发 | 直接安装 | **发布到「潘塔纳尔服务库」** |

文档原文：

> 卡片当前承载三类产品功能……**实时活动类**：承载用户确定、实时信息的服务形态，
> 例如正在通话中、正在录音中。**该类信息在流体云入口中展示。**

这解释了一直观察到的现象：**应用从未出现在「设置 → 通知与控制中心 → 流体云」列表里。**
那个列表列的是**服务**，不是 Android 应用。系统自带的计时器、音乐能显示，是因为它们走内置通道。

### 所以现在的通知长什么样

既然这条路走不通，就把实时活动那一整套全撤掉了——`ProgressStyle`、`setColorized`、
`setShortCriticalText`、进度条上的滑块图标、`setRequestPromotedOngoing`。

现在只用最普通的原生元素：

```
[小图标]  专注                        23:43
          保持专注
──────────────────────────────────────
[暂停]  [跳过]
```

- 标题只放阶段名，正文只放任务或状态，**两者都不含时间**。
  自己拼的时间字符串只在下发通知那一刻是对的，之后不会变，会造成"两个时间、下面那个不动"
- 时间统一交给系统 chronometer，它会自己持续走动
- 进度条靠重新下发通知推进（系统不会自己动它），所以剩余秒数每变一次就刷新一次
- 通知保留 `setColor(accent)`：它只会给应用图标垫一层底色，**不会**影响通知背景（背景染色要 `setColorized`）

将来如果 OPPO 那边打通了要重做，判定条件在上面的表格里已经记全，把这些设置加回来即可。
## 媒体卡片模式

标准实时活动那条路走不通之后，换了个思路：**不去争流体云的「实时活动」入口，而是借用它的「音乐」入口。**

系统从不区分媒体会话里装的是音乐还是别的东西——只要存在一个活跃的、状态为播放的 `MediaSession`，
它就会把会话的标题、封面、进度和操作按钮画到媒体卡片上。OPPO 的音乐流体云读的也是同一个东西。
所以计时器可以把自己伪装成一个正在播放的媒体：

```kotlin
// 标题 = 阶段名，副标题 = 任务名，时长 = 阶段总时长，位置 = 已过去的时间
session.setMetadata(MediaMetadataCompat.Builder()
    .putString(METADATA_KEY_TITLE, "专注")
    .putString(METADATA_KEY_ARTIST, "英语")
    .putLong(METADATA_KEY_DURATION, totalSeconds * 1000L)
    .putBitmap(METADATA_KEY_ALBUM_ART, launcherIconBitmap)
    .build())
session.setPlaybackState(PlaybackStateCompat.Builder()
    .setState(PlaybackStateCompat.STATE_PLAYING, elapsedMillis, 1f)
    .build())

// 会话必须是"真的在播放"，所以配一条静音音轨循环播放
mediaPlayer = MediaPlayer().apply { /* res/raw/silence.wav, isLooping = true */ }
```

几个刻意的取舍：

- **不申请音频焦点。** 静音音轨没有任何声音需要被听见，申请焦点只会把用户正在听的音乐打断，
  那才是真正影响使用的事。代价是调媒体音量时音量条会出现，但没有任何声音。
- **前台服务声明成 `mediaPlayback` 类型**（与 `specialUse` 并列），否则系统不会把这个会话
  当成正在播放的媒体。但只在真的在播放时才这么声明——`mediaPlayback` 型前台服务要求启动时
  确实处于播放状态，「开始后立刻暂停」那条路径必须退回 `specialUse`，否则系统会拒绝。
- **通知换成 `MediaStyle`** 并绑定会话 token。操作按钮必须给真实图标：
  媒体卡片的紧凑视图只显示图标，图标传 0 会渲染成一片空白。
- **封面用启动图标渲染成位图。** 自适应图标是 XML，`BitmapFactory` 解不了，只能走 `Drawable` 绘制。
- 媒体卡片的进度条用的是 `position / duration`，而会话只能往前走、做不出倒计时，
  所以卡片上显示的是「已过去 / 总时长」，不是剩余时间。

### 封面

系统会把封面**拉大铺满整张卡片当背景**，所以放什么都不如放一张图或者一片渐变好看。
设置里（**通知 → 媒体卡片封面**）给了三个来源：

| 选项 | 说明 |
| --- | --- |
| 主题色渐变 | 默认。从阶段强调色到压暗同色的对角渐变，专注一个色、休息一个色，永远不会难看 |
| 应用图标 | 就是启动图标，尺寸拉大之后比较突兀，但保留着 |
| 自定义图片 | 从相册选一张 |

自定义图片的实现上有几个考虑：

- **拷贝进应用私有目录**（`filesDir/media_artwork.jpg`），而不是保存相册的 content URI。
  URI 的读取权限可能被回收，也可能因为清理相册、换机而失效。
- **解码分两趟**：先只读尺寸算出采样率，再真正解码。现在随手一拍就是几千万像素，
  直接整张解码进内存很容易 OOM。之后居中裁成正方形、缩到 1024px、存成 JPEG。
- **用 `PickVisualMedia`** 而不是旧的 `GET_CONTENT`：Android 13+ 走系统照片选择器，
  不需要申请任何读相册权限，低版本自动回落到文档选择器。
- **缓存键里带自定义文件的时间戳**，所以换了图片不需要额外的通知机制，
  下一次同步就会重新加载；自定义图片读不出来时回退成渐变，而不是给一张空白封面。

### 代价

- **会和音乐抢媒体控件。** 同一时刻系统只把最活跃的那个会话放进卡片，计时开始时音乐卡片会被挤掉。
  音乐重新播放能抢回来（Android 13+ 也可以左右滑动切换会话）。
- **静音音轨是真实播放的**，音频管线全程运行，一个番茄会实打实耗一点电。
- 上架 Google Play 会被认定为「用媒体播放规避后台限制」。自用和侧载无所谓。

因此它在设置里是一个开关（**通知 → 媒体卡片模式**），**默认关闭**——副作用不小，
由用户自己决定要不要开。

开启后设置里会多一句提示：**ColorOS 17 / Flyme 用户建议在系统设置里打开「多应用同时输出音频」**，
否则静音音轨可能按音频焦点策略顶掉正在播放的音乐。

## 控制中心磁贴

把「开始专注」加到快捷设置里，点一下开始计时、再点一下暂停，磁贴副标题实时显示当前阶段和剩余时间。

磁贴服务与主应用同进程，直接拿 `PomodoroApplication` 上那个应用级的 `TimerController`，
和界面用的是同一份状态，不存在两套计时互相打架的问题。

有一个需要兜底的地方：**Android 12 起应用在后台默认不能拉起前台服务**。
从磁贴点进来属于「用户与应用 UI 交互」，正常情况下会被放行，但不能做这个假设——
所以 `TimerService.start()` 会返回是否成功，失败时磁贴会调 `startActivityAndCollapse()`
把界面带到前台，在那里再启动就是合法的。否则计时会在没有前台服务的情况下裸跑，随时被系统回收。

## 构建

常规做法：用 Android Studio 打开本工程，或在装好 JDK 17 与 Android SDK 36 的机器上直接跑 wrapper。

```powershell
.\gradlew.bat assembleDebug      # 产物 app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat assembleRelease    # 产物 app/build/outputs/apk/release/app-release.apk
.\gradlew.bat testDebugUnitTest  # 45 个单元测试
```

编译参数：`compileSdk` 与 `targetSdk` 都是 36（Android 16），`minSdk` 26，AGP 8.9.3，Gradle 8.11.1。
release 开了 R8 与资源压缩，包体约 1.4 MB；默认用工程内的调试密钥签名，可以直接装机，
正式发布请换成自己的 keystore。

### 本机的自包含工具链（不在仓库里）

开发这个项目的那台机器上**没有装过任何 Android 环境**，所以工程目录里自带了一套
（JDK 17 + Android SDK 36 + Gradle 缓存，约 3 GB），放在 `.toolchain/`。
**它被 `.gitignore` 排除，克隆下来是不会有这个目录的**，正常用 Android Studio 就好。

如果确实要用它，带上这些环境变量：

```powershell
$root = (Get-Location).Path
$env:JAVA_HOME        = "$root\.toolchain\jdk"
$env:ANDROID_HOME     = "$root\.toolchain\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = "$root\.toolchain\gradle-home"
$env:TMP = $env:TEMP  = "$root\.toolchain\tmp"   # Gradle 原生库要解压到可写目录

.\gradlew.bat assembleDebug
```

### 受限环境下的测试

如果在禁止打开命名管道的沙箱里跑，`gradlew testDebugUnitTest` 会报
`Could not write standard input to Gradle Test Executor`——Gradle 的测试 worker 用管道 stdio
和主进程通信，worker 会直接崩掉。这种情况换成：

```powershell
.\tools\run-tests.ps1
```

它让 Gradle 只导出测试运行时 classpath，再用 JUnitCore 直接执行。两种方式跑的是同一批 class、
同一套断言，只是绕开了管道。

45 个用例覆盖：状态机（阶段流转、暂停恢复、后台追赶、跳过语义、配置收敛、**跨重启快照校验**）、
统计口径（按天分组、连续达标、目标缺口补零）、序列化往返。

## 辅助脚本

`tools/` 里分成两类：**项目资产**（和代码一起维护，改了图标或音轨就要跑）和
**本机环境辅助**（只对那套自包含工具链有意义，克隆下来用不上）。

| 脚本 | 类别 | 用途 |
| --- | --- | --- |
| `tools\icon-source.png` | 项目资产 | 图标源图（白图黑底），下面那个脚本的输入 |
| `tools\make-icon-assets.ps1` | 项目资产 | 从源图生成 `ic_launcher_foreground.png` 与 `ic_app_mark.png` |
| `tools\preview-icon.mjs` | 项目资产 | 解析并校验矢量图标路径，打印 ASCII 预览 |
| `tools\make-silence.mjs` | 项目资产 | 生成媒体卡片模式用的静音音轨（`res/raw/silence.wav`） |
| `tools\run-tests.ps1` | 环境辅助 | 受限环境下跑单元测试的替代方案 |
| `tools\classpath.init.gradle` | 环境辅助 | 给上面那个脚本用的 Gradle 初始化脚本，导出测试 classpath |
| `tools\gw.ps1` | 环境辅助 | 带上 `.toolchain/` 环境跑 Gradle |
| `tools\dl.mjs` / `speed.mjs` | 环境辅助 | 下载与测速（用来挑国内镜像） |

### 图标是怎么来的

启动图标和通知小图标**不是手画的矢量**，而是从 `tools/icon-source.png` 生成的位图：

```powershell
powershell -File tools\make-icon-assets.ps1
```

它会做三件事：读出源图的墨迹包围盒、把黑底按亮度抠成全透明（黑→透明，白→不透明，
边缘靠抗锯齿自然过渡），然后按两种取景各导一份——

- `ic_launcher_foreground.png`：图案占画面 70%，保证落在自适应图标的安全区内
- `ic_app_mark.png`：图案占 94%，几乎填满，供通知小图标使用

**为什么要抠成透明而不是直接用源图**：通知小图标和控制中心磁贴图标，系统**只取 alpha 通道**
然后统一染色。源图是白图黑底、alpha 全不透明，直接放上去会被渲染成一个实心方块。

矢量那份只留给控制中心磁贴（`ic_tile.xml`，圆形时钟造型）——它在十几个 dp 下
比番茄的蒂更容易辨认。改了它之后跑 `node tools\preview-icon.mjs` 核对。

`gw.ps1` 和 `run-tests.ps1` 带 UTF-8 BOM。这不是洁癖：Windows PowerShell 5.1 对无 BOM 的脚本
按 GBK 解码，中文注释会被解错并吃掉换行，直接导致语法错误。
`make-icon-assets.ps1` 是纯 ASCII 的，所以不受这条影响。

## 安装到手机

手机开 USB 调试后：

```powershell
.\.toolchain\android-sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

或者把 apk 拷到手机里点击安装。

## 用 Android Studio 打开

直接把工程目录作为一个现有项目打开即可。Android Studio 会自己生成 `local.properties` 指向你本机的 SDK。
两点需要注意：

- 项目目录含中文，`gradle.properties` 里已经设了 `android.overridePathCheck=true` 绕过 AGP 的路径检查。
  若想彻底避免这类问题，可以把工程移到纯英文路径。
- `gradle/wrapper/gradle-wrapper.properties` 里的发行版地址换成了腾讯云镜像。
  在海外或镜像失效时，换回 `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip`。

## 设计取舍

**为什么页签切换不用 Navigation Compose。** NavHost 的转场底层是 `AnimatedContent`，
切换时把新旧两个页面当成两棵独立子树分别组合、测量、加裁剪层，而且**目标页是在动画开始的
那一刻才第一次组合的**——一个稍重的页面组合十几毫秒，动画第一帧就直接掉了。
换成 `HorizontalPager` 后，切页退化成 LazyLayout 的滚动（只改放置位置，是开销最小的一类动画），
`beyondViewportPageCount = 1` 还能让相邻页提前组合好，动画开始时目标页已经在了。
顺带白拿了手指滑动切页。页面滚动位置由 LazyLayout 的 saveable 机制保存，切走再切回来不会丢。
既然不再需要导航库，`navigation-compose` 依赖也一并去掉了。

**为什么动画状态不写成 `by animateXxxAsState(...)`。** 这是本项目最大的一处性能坑。
用 `by` 解构会在**组合阶段**读取动画值，于是动画每推进一帧就要重新执行一次组合：
计时环的进度每秒变一次、动画 400ms，等于每秒有二十多帧在重组整个表盘，
切页时它还在组合中，直接和转场动画抢帧。
正确做法是保持成 `State`，把 `.value` 的读取放进 `Canvas` 的绘制 lambda 里——
动画就只驱动绘制阶段，组合阶段完全不受影响。这就是 Compose 性能准则里的
"把状态读取推迟到尽可能低的阶段"。

**为什么计时状态不逐秒落盘。** 计时快照恢复靠的是 `deadlineElapsedRealtime`，
它在整个阶段内恒定不变；剩余秒数每秒都在变，但那些中间值恢复时根本用不到。
早先的实现在每次 tick 后都写一次 DataStore，等于每秒一次序列化 + 原子写 + 改名，
纯属浪费，还会和界面动画抢 I/O。现在只在阶段、运行状态、总时长这些结构性字段变化时才落盘。

**为什么用截止时刻而不是倒计时累加。** 倒计时靠协程每秒递减，一旦进程被系统冻结，
回到前台就会少走一截，长后台场景尤其明显。改成记录 `deadlineElapsedRealtime` 之后，
任意时刻的剩余时间都是算出来的，后台待多久都不会偏。副作用是后台停留过久时，
一次 `advance` 可能要补齐好几个阶段——引擎里那个循环就是干这个的，上限 64 步防止异常配置导致死循环。

**为什么统计用墙上时间、计时用单调时钟。** 计时必须用 `elapsedRealtime`，否则用户改系统时间就会把计时搞乱；
但统计要按"哪天"分组，只能靠墙上时间。两者在 `TimerController.recordCompletion` 里换算，
并且按**实际截止时刻**而不是**发现时刻**折算，避免后台停留导致记录时间偏移。

**为什么用 DataStore + JSON 而不是 Room。** 数据量很小（任务几百条、记录几千条，
仓库里也做了 5000 条的滚动上限），用 Room 要引入 KSP 注解处理，
构建复杂度和收益不成正比。DataStore 同样有原子写入和 Flow 订阅。

**为什么图表自己画。** 一个 7 天柱状图不值得引入 Vico 之类的依赖。
`StatsScreen` 里几十行 Canvas 就够，还能顺手把目标线画进去。

**通知渠道为什么是静音的。** 用户能关掉响铃和震动，但如果声音交给通知渠道，
关掉开关后系统照样会响。所以渠道保持静音，声音和震动由 `Alerts` 按设置自己控制。

## 已知限制

- **流体云的「实时活动」入口没有接入。** 详细原因见「关于流体云」一节：OPPO 给第三方应用的入口是
  「潘塔纳尔泛在服务」，要用他们的专有 IDE 把 JS/CSS 打成 `.upk` 并发布到服务库，
  和本工程是两套东西。标准 Android 实时活动那条路已完整实现并逐项验证过判定条件，
  但系统始终不提升，实现最终撤掉了。**现在走的是「媒体卡片模式」这条替代路线。**
- **媒体卡片模式已在一加 PJZ110 / Android 17 上验证可用**：锁屏播放器和桌面音乐卡片都能显示
  倒计时，进度条随计时推进，暂停/跳过按钮工作正常。但这套做法建立在"系统把静音音轨的会话
  当成活跃媒体"之上，换到别的 ROM 或厂商收紧静音音频管控后未必成立，目前只有这一台机器的实测数据。
- **已在真机上验证过的部分**：一加 PJZ110 / Android 17（API 37）。计时、通知、
  响铃震动、任务与统计、媒体卡片模式都能正常使用。期间修掉了两个真机上才暴露的问题——
  重启后 `elapsedRealtime` 归零导致剩余时间显示成 32 小时（`PomodoroEngine.restore`），
  以及 `setColorized` 让通知变成大色块卡片。
- **还没验证过的部分**：各厂商 ROM 的后台保活策略（本机是 ColorOS/OxygenOS），
  以及长按通知、锁屏通知等系统入口下的表现。
- `targetSdk` 是 36。Android 16 对 targetSdk 36 的应用有行为变更（例如预测性返回默认开启），
  本应用没有自定义返回逻辑，目前判断影响很小，但同样没有真机验证。
- 前台服务用的是 `specialUse` 类型。自用和侧载没问题，上架 Google Play 需要说明用途。
- 没有云同步，数据只在本机 DataStore 里；`android:allowBackup` 已开启，换机时可随系统备份迁移。
- 只统计专注阶段，休息不计入任何统计。
- 没有 widget 和 Wear OS 支持。
