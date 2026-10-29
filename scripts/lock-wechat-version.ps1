param(
    [string]$Installer = "D:\wechat_bot\windows-agent\prebuilt\WeChatWin_4.1.10.27.exe",
    [string]$LogPath = "D:\wechat_bot\logs\wechat-lock.log",
    [switch]$Unlock
)

$ErrorActionPreference = 'Stop'
$dir = "C:\Program Files\Tencent\Weixin"
$hookDll = "D:\wechat_bot\windows-agent\prebuilt\version.dll"

function Write-Log([string]$message) {
    $line = "$(Get-Date -Format o) $message"
    Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
    Write-Host $line
}

Write-Log "lock-version start unlock=$Unlock"

Get-Process Weixin -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 4

# always clear previous deny ACE so the installer can write
& icacls $dir /remove:d Everyone 2>&1 | Out-Null
Write-Log "deny ACE cleared"

if (-not $Unlock) {
    $proc = Start-Process -FilePath $Installer -ArgumentList '/S' -PassThru
    $proc | Wait-Process -Timeout 900 -ErrorAction SilentlyContinue
    Write-Log "installer exit=$($proc.ExitCode)"
    Start-Sleep -Seconds 5

    $exe = Get-Item (Join-Path $dir 'Weixin.exe')
    Write-Log "weixin.exe version=$($exe.VersionInfo.FileVersion) size=$($exe.Length)"
    Write-Log ("version dirs=" + ((Get-ChildItem $dir -Directory | Select-Object -ExpandProperty Name) -join ','))

    Copy-Item -LiteralPath $hookDll -Destination (Join-Path $dir 'version.dll') -Force
    Write-Log ("hook dll=" + (Get-FileHash (Join-Path $dir 'version.dll') -Algorithm SHA256).Hash.ToLower())

    & icacls $dir /deny "Everyone:(OI)(CI)(W,D)" 2>&1 | Out-Null
    Write-Log "deny write ACE applied"
}

Write-Log "lock-version done"
