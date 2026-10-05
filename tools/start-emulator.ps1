# 启动 Speed_API36 模拟器
#
# 用法：在项目根目录或任意位置执行
#     .\tools\start-emulator.ps1
#
# 可选参数会透传给 emulator.exe，例如：
#     .\tools\start-emulator.ps1 -no-snapshot-load    # 强制冷启动
#     .\tools\start-emulator.ps1 -wipe-data           # 清空数据重来
#
# 说明：本机 AVD 存放在 F:\Android\avd（不在默认的 %USERPROFILE%\.android\avd），
# 所以必须设置 ANDROID_AVD_HOME，否则 emulator 找不到设备。

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $EmulatorArgs
)

$ErrorActionPreference = 'Stop'

$env:ANDROID_HOME     = 'F:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'F:\Android\Sdk'
$env:ANDROID_AVD_HOME = 'F:\Android\avd'
$env:ANDROID_USER_HOME = 'F:\Android\android-user-home'

$avdName   = 'Speed_API36'
$emulator  = 'F:\Android\Sdk\emulator\emulator.exe'

if (-not (Test-Path $emulator)) {
    throw "找不到 emulator.exe：$emulator"
}

$available = & $emulator -list-avds
if ($available -notcontains $avdName) {
    throw "找不到 AVD '$avdName'。现有 AVD：$($available -join ', ')"
}

if (-not $EmulatorArgs -or $EmulatorArgs.Count -eq 0) {
    $EmulatorArgs = @('-no-boot-anim', '-no-audio')
}

Write-Host "启动模拟器 $avdName ..." -ForegroundColor Cyan
Write-Host "（首次冷启动约 1-2 分钟；已保存快照时几秒即可）" -ForegroundColor DarkGray

& $emulator -avd $avdName @EmulatorArgs
