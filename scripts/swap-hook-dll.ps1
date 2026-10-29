param(
    [Parameter(Mandatory = $true)][string]$DllPath,
    [string]$LogPath = "D:\wechat_bot\logs\hook-swap.log"
)

$ErrorActionPreference = 'Stop'
$dir = "C:\Program Files\Tencent\Weixin"

function Write-Log([string]$message) {
    $line = "$(Get-Date -Format o) $message"
    Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
    Write-Host $line
}

Write-Log "swap-only dll=$DllPath"
Get-Process Weixin -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 4

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
Copy-Item -LiteralPath (Join-Path $dir 'version.dll') -Destination (Join-Path $dir "version.dll.bak-$stamp") -Force
Copy-Item -LiteralPath $DllPath -Destination (Join-Path $dir 'version.dll') -Force
Write-Log ("installed=" + (Get-FileHash (Join-Path $dir 'version.dll') -Algorithm SHA256).Hash.ToLower())
Write-Log "swap-only done"
