param(
    [int]$StartPort = 30001,
    [string]$LogPath = "D:\wechat_bot\logs\hook-swap.log"
)

function Write-Log([string]$message) {
    $line = "$(Get-Date -Format o) $message"
    Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
    Write-Host $line
}

Write-Log "kill-and-launch: stopping weixin"
Get-Process Weixin -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 4

Write-Log "launching via explorer (medium integrity)"
Start-Process explorer.exe -ArgumentList 'D:\wechat_bot\scripts\start-wechat.cmd'
Start-Sleep -Seconds 6

$status = $null
for ($i = 0; $i -lt 45; $i++) {
    Start-Sleep -Seconds 2
    try {
        $status = Invoke-RestMethod "http://127.0.0.1:$StartPort/QueryDB/status" -TimeoutSec 3
        break
    } catch {
    }
}

if ($null -eq $status) {
    Write-Log "hook still down after relaunch"
} else {
    Write-Log ("status=" + ($status | ConvertTo-Json -Compress))
    try {
        $dbs = (Invoke-WebRequest -Method Post "http://127.0.0.1:$StartPort/QueryDB/GetAllDBName" `
                -ContentType 'application/json' -Body '{}' -TimeoutSec 8).Content
        Write-Log ("dbs=" + $dbs)
    } catch {
        Write-Log ("dbs_err=" + $_.Exception.Message)
    }
}
Write-Log "kill-and-launch done"
