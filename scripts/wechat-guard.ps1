param(
    [int]$Port = 30001,
    [string]$WeChatDir = "C:\Program Files\Tencent\Weixin",
    [string]$ExpectedVersion = "4.1.10.27",
    [string]$ExpectedDllHash = "23948e7d933ff40735ddc0cc516641fd9f935d67ffdec476bb8db61b79940f29",
    [string]$CallbackUrl = "http://127.0.0.1:18080/api/wechat/hook/callback",
    [string]$StatusPath = "D:\wechat_bot\logs\guard-status.json",
    [string]$LogPath = "D:\wechat_bot\logs\guard.log",
    [int]$IntervalSeconds = 10,
    [int]$FailureThreshold = 3,
    [int]$MaxRestartsPerHour = 5,
    [switch]$Once
)

$ErrorActionPreference = 'Continue'

$script:exe = Join-Path $WeChatDir 'Weixin.exe'
$script:dll = Join-Path $WeChatDir 'version.dll'
$script:ini = Join-Path $WeChatDir 'wx.ini'
$script:failures = 0
$script:restarts = 0
$script:restartTimes = New-Object System.Collections.Generic.List[datetime]
$script:lastAction = 'none'
$script:lastError = ''
$script:instance = ''
$script:portUp = $false

function Write-GuardLog([string]$message) {
    $line = "$(Get-Date -Format o) $message"
    try {
        Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
    } catch {
    }
}

function Write-Status([string]$level) {
    $proc = Get-Process Weixin -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle } | Select-Object -First 1
    if (-not $proc) {
        $proc = Get-Process Weixin -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    $version = ''
    $dllHashOk = $false
    $iniOk = $false
    try { $version = (Get-Item $script:exe).VersionInfo.FileVersion } catch { }
    try { $dllHashOk = ((Get-FileHash $script:dll -Algorithm SHA256).Hash.ToLower() -eq $ExpectedDllHash) } catch { }
    try { $iniOk = (Test-Path $script:ini) -and ((Get-Content $script:ini -Raw) -match [regex]::Escape($CallbackUrl)) } catch { }

    $status = [ordered]@{
        timestamp   = (Get-Date -Format o)
        level       = $level
        pid         = if ($proc) { $proc.Id } else { 0 }
        hasWindow   = if ($proc) { [bool]$proc.MainWindowTitle } else { $false }
        version     = $version
        versionOk   = ($version -eq $ExpectedVersion)
        dllHashOk   = $dllHashOk
        wxIniOk     = $iniOk
        callbackUrl = $CallbackUrl
        port        = $Port
        portUp      = $script:portUp
        instance    = $script:instance
        failures    = $script:failures
        restarts    = $script:restarts
        lastAction  = $script:lastAction
        lastError   = $script:lastError
    }
    $json = $status | ConvertTo-Json -Depth 4
    $tmp = "$StatusPath.tmp"
    [System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $tmp -Destination $StatusPath -Force
}

function Start-WeChat {
    try {
        $launchArgs = @("StartPort=$Port", "CallBackURL=`"$CallbackUrl`"")
        if (Test-Path $script:ini) {
            # wx.ini already carries recv_url; keep args minimal so a missing ini still works
            $launchArgs = @("StartPort=$Port")
        }
        Start-Process -FilePath $script:exe -ArgumentList $launchArgs -WorkingDirectory $WeChatDir | Out-Null
        $script:lastAction = 'started'
        Write-GuardLog ("wechat started args=" + ($launchArgs -join ' '))
        Start-Sleep -Seconds 8
    } catch {
        $script:lastError = $_.Exception.Message
        Write-GuardLog "start failed: $($script:lastError)"
    }
}

function Restart-WeChat {
    $now = Get-Date
    while ($script:restartTimes.Count -gt 0 -and ($now - $script:restartTimes[0]).TotalHours -ge 1) {
        $script:restartTimes.RemoveAt(0)
    }
    if ($script:restartTimes.Count -ge $MaxRestartsPerHour) {
        $script:lastAction = 'restart-suppressed'
        Write-GuardLog "restart suppressed: $MaxRestartsPerHour per hour reached"
        return
    }
    try {
        Get-Process Weixin -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 4
        Start-WeChat
        $script:restartTimes.Add($now)
        $script:restarts++
        $script:lastAction = 'restarted'
        Write-GuardLog 'wechat restarted by guard'
    } catch {
        $script:lastError = $_.Exception.Message
        Write-GuardLog "restart failed: $($script:lastError)"
    }
}

Write-GuardLog "guard started port=$Port callback=$CallbackUrl expectedVersion=$ExpectedVersion"

do {
    $script:portUp = $false
    $version = ''
    try { $version = (Get-Item $script:exe).VersionInfo.FileVersion } catch { $script:lastError = $_.Exception.Message }

    if ($version -and $version -ne $ExpectedVersion) {
        $script:lastAction = 'version-mismatch'
        Write-GuardLog "version mismatch: installed=$version expected=$ExpectedVersion (no restart)"
        Write-Status 'error'
        Start-Sleep -Seconds ([Math]::Max($IntervalSeconds, 30))
        continue
    }

    if (-not (Get-Process Weixin -ErrorAction SilentlyContinue)) {
        $script:lastAction = 'process-missing'
        Start-WeChat
        Write-Status 'warn'
        if ($Once) { break }
        Start-Sleep -Seconds $IntervalSeconds
        continue
    }

    try {
        $status = Invoke-RestMethod "http://127.0.0.1:$Port/QueryDB/status" -TimeoutSec 2
        $script:portUp = $true
        $script:instance = [string]$status.hWeixin
        $script:failures = 0
        $script:lastError = ''
    } catch {
        $script:portUp = $false
        $script:lastError = $_.Exception.Message
        $script:failures++
        Write-GuardLog "probe failed ($($script:failures)/$FailureThreshold): $($script:lastError)"
        if ($script:failures -ge $FailureThreshold) {
            $script:failures = 0
            Restart-WeChat
        }
    }

    if ($script:portUp) { Write-Status 'ok' } else { Write-Status 'warn' }
    if ($Once) { break }
    Start-Sleep -Seconds $IntervalSeconds
} while ($true)
