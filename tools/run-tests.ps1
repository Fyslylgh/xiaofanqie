# 跑单元测试。
#
# 为什么不用 `gradlew testDebugUnitTest`：Gradle 的测试 worker 通过管道 stdio 与主进程通信，
# 在受限沙箱下会报 "Could not write standard input to Gradle Test Executor" 而整个 worker 崩掉。
# 这里改成让 Gradle 导出测试运行时 classpath，再直接用 JUnitCore 执行，
# 结果和 Gradle 跑出来的一致，只是绕开了管道。
#
# 在普通（无沙箱限制）的环境里，直接 `.\gradlew.bat testDebugUnitTest` 即可，不需要这个脚本。
param([string[]]$Tests = @(
        "com.fysly.pomodoro.domain.PomodoroEngineTest",
        "com.fysly.pomodoro.data.StatsCalculatorTest",
        "com.fysly.pomodoro.data.SerializationTest"
    ))

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME = Join-Path $root ".toolchain\jdk"
$env:ANDROID_HOME = Join-Path $root ".toolchain\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $root ".toolchain\gradle-home"
$env:TMP = Join-Path $root ".toolchain\tmp"
$env:TEMP = $env:TMP
New-Item -ItemType Directory -Path $env:TMP -Force | Out-Null

# bundleDebugClassesToRuntimeJar 就是 classpath 里那份主代码产物，必须一起重建，
# 否则测试跑的是上一次编译的旧 class。
& (Join-Path $root "gradlew.bat") -I (Join-Path $root "tools\classpath.init.gradle") `
    :app:bundleDebugClassesToRuntimeJar `
    :app:compileDebugUnitTestKotlin `
    :app:dumpTestClasspath `
    --console=plain | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Error "编译测试代码失败"; exit 1 }

$cpFile = Join-Path $root ".toolchain\test-classpath.txt"
$cp = [System.IO.File]::ReadAllText($cpFile, [System.Text.Encoding]::UTF8)

& (Join-Path $env:JAVA_HOME "bin\java.exe") -cp $cp org.junit.runner.JUnitCore @Tests
exit $LASTEXITCODE
