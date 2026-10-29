param(
    [string]$Installer = "D:\wechat_bot\windows-agent\prebuilt\WeChatWin_4.1.10.27.exe",
    [string]$LogPath = "D:\wechat_bot\logs\wechat-install.log",
    [switch]$SkipUpdateBlock
)

$ErrorActionPreference = 'Stop'
$dir = "C:\Program Files\Tencent\Weixin"
$hookDll = "D:\wechat_bot\windows-agent\prebuilt\version.dll"
$hosts = "$env:SystemRoot\System32\drivers\etc\hosts"

function Write-Log([string]$message) {
    $line = "$(Get-Date -Format o) $message"
    Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
    Write-Host $line
}

Write-Log "install start installer=$Installer"

Get-Process Weixin -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 4

Write-Log "running installer silently"
$proc = Start-Process -FilePath $Installer -ArgumentList '/S' -PassThru
$proc | Wait-Process -Timeout 900 -ErrorAction SilentlyContinue
Write-Log "installer exit=$($proc.ExitCode)"

Start-Sleep -Seconds 5

$exe = Get-Item (Join-Path $dir 'Weixin.exe') -ErrorAction SilentlyContinue
if ($exe) {
    Write-Log "weixin.exe size=$($exe.Length) version=$($exe.VersionInfo.FileVersion)"
} else {
    Write-Log "weixin.exe missing"
}

$versionDirs = Get-ChildItem $dir -Directory | Select-Object -ExpandProperty Name
Write-Log ("version dirs=" + ($versionDirs -join ','))

if (-not $SkipUpdateBlock) {
    $blocked = @('dldir1.qq.com', 'dldir1v6.qq.com')
    $current = Get-Content -LiteralPath $hosts -Encoding UTF8 -ErrorAction SilentlyContinue
    $added = 0
    foreach ($domain in $blocked) {
        if ($current -notmatch [regex]::Escape($domain)) {
            Add-Content -LiteralPath $hosts -Value "127.0.0.1 $domain" -Encoding UTF8
            $added++
        }
    }
    Write-Log "hosts entries added=$added"
}

if (Test-Path -LiteralPath $hookDll) {
    Copy-Item -LiteralPath $hookDll -Destination (Join-Path $dir 'version.dll') -Force
    Write-Log ("hook dll=" + (Get-FileHash (Join-Path $dir 'version.dll') -Algorithm SHA256).Hash.ToLower())
}

Write-Log "install done"
