param(
    [Parameter(Mandatory = $true)][string]$DllPath,
    [int]$StartPort = 30001,
    [string]$LogPath = "D:\wechat_bot\logs\hook-swap.log"
)

$ErrorActionPreference = 'Stop'
$dir = "C:\Program Files\Tencent\Weixin"
$exe = Join-Path $dir 'Weixin.exe'

function Write-Log([string]$message) {
    $line = "$(Get-Date -Format o) $message"
    Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
    Write-Host $line
}

Write-Log "start dll=$DllPath port=$StartPort"

Get-Process Weixin -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 3

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
Copy-Item -LiteralPath (Join-Path $dir 'version.dll') -Destination (Join-Path $dir "version.dll.bak-$stamp") -Force
Write-Log "backup=version.dll.bak-$stamp"
Copy-Item -LiteralPath $DllPath -Destination (Join-Path $dir 'version.dll') -Force
Write-Log ("installed=" + (Get-FileHash (Join-Path $dir 'version.dll') -Algorithm SHA256).Hash.ToLower())

Start-Process -FilePath $exe -ArgumentList "StartPort=$StartPort" -WorkingDirectory $dir
Write-Log "wechat started"

$status = $null
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    try {
        $status = Invoke-RestMethod "http://127.0.0.1:$StartPort/QueryDB/status" -TimeoutSec 3
        break
    } catch {
    }
}

if ($null -eq $status) {
    Write-Log "hook did not respond on port $StartPort"
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
Write-Log "done"
