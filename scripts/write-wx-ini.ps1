param(
    [string]$CallbackUrl = "http://127.0.0.1:18080/api/wechat/hook/callback",
    [string]$WeChatDir = "C:\Program Files\Tencent\Weixin",
    [string]$LogPath = "D:\wechat_bot\logs\wx-ini.log"
)

$ErrorActionPreference = 'Stop'

$iniPath = Join-Path $WeChatDir 'wx.ini'
$content = "[wx]`r`nrecv_url=$CallbackUrl`r`n"
[System.IO.File]::WriteAllText($iniPath, $content, (New-Object System.Text.UTF8Encoding($false)))

$line = "$(Get-Date -Format o) wx.ini written: $iniPath -> $CallbackUrl"
Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
Write-Host $line
Get-Content -LiteralPath $iniPath
