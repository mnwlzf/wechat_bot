param(
    [string]$LogPath = "D:\wechat_bot\logs\wechat-antiupdate.log"
)

$ErrorActionPreference = 'Stop'
$dir = "C:\Program Files\Tencent\Weixin"
$user = "$env:USERDOMAIN\$env:USERNAME"
$updateRoot = Join-Path $env:APPDATA 'Tencent\xwechat\update'
$updateData = Join-Path $updateRoot 'update.data'
$patchDir = Join-Path $updateRoot 'patch'

function Write-Log([string]$message) {
    $line = "$(Get-Date -Format o) $message"
    Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
    Write-Host $line
}

Write-Log "antiupdate start user=$user"

# 1) remove the install-dir deny that blocked the client from starting
& icacls $dir /remove:d Everyone 2>&1 | Out-Null
Write-Log "install-dir deny removed"

# 2) vendor method: block the updater staging area under the user profile
if (-not (Test-Path $updateRoot)) { New-Item -ItemType Directory -Path $updateRoot -Force | Out-Null }
if (-not (Test-Path $patchDir)) { New-Item -ItemType Directory -Path $patchDir -Force | Out-Null }
Get-ChildItem $patchDir -Force -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
if (-not (Test-Path $updateData)) { New-Item -ItemType File -Path $updateData -Force | Out-Null }

& icacls $updateData /deny "${user}:(N)" 2>&1 | Out-Null
& icacls $patchDir /deny "${user}:(OI)(CI)(N)" 2>&1 | Out-Null
Write-Log "updater staging area denied"

Write-Log ("update.data exists=" + (Test-Path $updateData) + " patch exists=" + (Test-Path $patchDir))
Write-Log "antiupdate done"
