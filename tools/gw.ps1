# 用项目内置工具链跑 Gradle，不污染系统环境变量。
# 用法:  pwsh -File tools\gw.ps1 assembleDebug
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)

$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = Join-Path $root ".toolchain\jdk"
$env:ANDROID_HOME = Join-Path $root ".toolchain\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $root ".toolchain\gradle-home"
# Gradle 的原生库要解压到临时目录，必须留在工作区内，否则会被沙箱拒绝
$env:TMP = Join-Path $root ".toolchain\tmp"
$env:TEMP = $env:TMP
New-Item -ItemType Directory -Path $env:GRADLE_USER_HOME, $env:TMP -Force | Out-Null

if (-not (Test-Path $env:JAVA_HOME)) { Write-Error "找不到 JDK: $env:JAVA_HOME"; exit 1 }
if (-not (Test-Path $env:ANDROID_HOME)) { Write-Error "找不到 Android SDK: $env:ANDROID_HOME"; exit 1 }

& (Join-Path $root "gradlew.bat") @GradleArgs
exit $LASTEXITCODE
