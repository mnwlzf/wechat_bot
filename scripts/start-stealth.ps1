#requires -version 7.0
<#
.SYNOPSIS
    以“低存在感”方式启动微信机器人服务：无控制台窗口、随机启动抖动、日志落盘。

.DESCRIPTION
    伪装层的运行期加固，解决三个容易被注意到的点：
      1. 控制台黑窗口 / 命令行里的业务关键词
      2. 每天固定秒级开机启动
      3. 日志直接打在当前终端里

    服务本体仍是 server/target 下的 Spring Boot JAR，这里只是换了一个
    中性文件名（wxsync.jar）并放到用户目录，PID 与日志也放在用户目录。

.PARAMETER JitterSeconds
    启动前随机等待的秒数上限，0 表示立即启动（调试用）。

.PARAMETER Foreground
    前台运行（继承当前控制台），用于排错；默认后台。

.PARAMETER Stop
    停止已启动的实例。

.PARAMETER InstallTask
    注册登录自启计划任务，并带 2 分钟随机延迟。
#>
param(
    [string]$Root = (Split-Path $PSScriptRoot -Parent),
    [string]$Jar = '',
    [int]$JitterSeconds = 90,
    [switch]$Foreground,
    [switch]$Stop,
    [switch]$InstallTask
)

$ErrorActionPreference = 'Stop'

$runtimeDir = Join-Path $env:LOCALAPPDATA 'WXSync'
$logDir = Join-Path $runtimeDir 'logs'
$runtimeJar = Join-Path $runtimeDir 'wxsync.jar'
$pidFile = Join-Path $runtimeDir 'service.pid'
New-Item -ItemType Directory -Force -Path $runtimeDir, $logDir | Out-Null

function Get-Javaw {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\javaw.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $found = Get-Command javaw.exe -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    throw '未找到 javaw.exe，请设置 JAVA_HOME 或把 JDK 加入 PATH'
}

if ($Stop) {
    if (Test-Path -LiteralPath $pidFile) {
        $old = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue
        if ($old) {
            Get-Process -Id ([int]$old) -ErrorAction SilentlyContinue | Stop-Process -Force
            Write-Host "[stealth] 已停止 PID $old"
        }
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    } else {
        Write-Host '[stealth] 没有找到 PID 文件'
    }
    return
}

# 统一从根目录 .env 注入配置
& (Join-Path $PSScriptRoot 'load-env.ps1') -Root $Root

if (-not $Jar) {
    $Jar = Join-Path $Root 'server\target\wechat-bot-server-0.1.0.jar'
}
if (-not (Test-Path -LiteralPath $Jar)) {
    throw "未找到 $Jar，请先执行：mvn -f server/pom.xml -DskipTests package"
}

# 仅在内容变化时覆盖，避免运行中替换文件
$source = Get-Item -LiteralPath $Jar
$needCopy = $true
if (Test-Path -LiteralPath $runtimeJar) {
    $target = Get-Item -LiteralPath $runtimeJar
    $needCopy = $source.Length -ne $target.Length -or $source.LastWriteTimeUtc -gt $target.LastWriteTimeUtc
}
if ($needCopy) {
    Copy-Item -LiteralPath $Jar -Destination $runtimeJar -Force
    Write-Host "[stealth] 已更新运行副本 $runtimeJar"
}

$javaw = Get-Javaw
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outLog = Join-Path $logDir "service-$stamp.log"
$errLog = Join-Path $logDir "service-$stamp.err.log"

$arguments = @(
    '-Xms256m',
    '-Xmx768m',
    '-Dfile.encoding=UTF-8',
    '-Dspring.main.banner-mode=off',
    '-jar', $runtimeJar
)

if ($InstallTask) {
    $action = New-ScheduledTaskAction -Execute $javaw -Argument ($arguments -join ' ') -WorkingDirectory $runtimeDir
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $trigger.RandomDelay = 'PT2M'
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
    Register-ScheduledTask -TaskName 'WXSyncService' -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
    Write-Host '[stealth] 已注册计划任务 WXSyncService（登录后 0~2 分钟随机启动）'
    return
}

if ($JitterSeconds -gt 0) {
    $wait = Get-Random -Minimum 0 -Maximum ($JitterSeconds + 1)
    Write-Host "[stealth] 随机延迟 $wait 秒后启动（模拟人工开机节奏）"
    Start-Sleep -Seconds $wait
}

if ($Foreground) {
    Push-Location $runtimeDir
    try {
        & $javaw @arguments
    } finally {
        Pop-Location
    }
    return
}

$process = Start-Process -FilePath $javaw -ArgumentList $arguments -WorkingDirectory $runtimeDir `
    -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog

Set-Content -LiteralPath $pidFile -Value $process.Id -Encoding ascii
Write-Host "[stealth] 已启动 PID $($process.Id)"
Write-Host "[stealth] 日志：$outLog"
Write-Host "[stealth] 停止：.\scripts\start-stealth.ps1 -Stop"
