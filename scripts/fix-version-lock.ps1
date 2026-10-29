param(
    [switch]$Apply,
    [string]$LogPath = "D:\wechat_bot\logs\wechat-lock.log"
)

$ErrorActionPreference = 'Stop'
$dir = "C:\Program Files\Tencent\Weixin"

function Write-Log([string]$message) {
    $line = "$(Get-Date -Format o) $message"
    Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
    Write-Host $line
}

Write-Log "fix-lock start apply=$Apply"
& icacls $dir /remove:d Everyone 2>&1 | Out-Null
Write-Log "deny ACE removed"

if ($Apply) {
    # WD/AD/WEA/WA = modify data, DC = delete child: blocks replacing files but keeps read/execute
    & icacls $dir /deny "Everyone:(OI)(CI)(WD,AD,WEA,WA,DC)" 2>&1 | Out-Null
    Write-Log "targeted deny applied"
}

$canRead = Test-Path (Join-Path $dir 'Weixin.exe')
Write-Log "read-ok=$canRead"

$writeBlocked = $false
try {
    $probe = Join-Path $dir 'probe.tmp'
    [IO.File]::WriteAllText($probe, 'x')
    Remove-Item $probe -Force
} catch {
    $writeBlocked = $true
}
Write-Log "write-blocked=$writeBlocked"
Write-Log "fix-lock done"
