param(
    [Parameter(Mandatory = $true)]
    [string]$WeChatDirectory,
    [int]$Port = 30001,
    [switch]$NoLaunch,
    [switch]$ConfigureLoopbackFirewall
)
$ErrorActionPreference = 'Stop'
$prebuiltDir = Join-Path $PSScriptRoot 'prebuilt'
$manifestPath = Join-Path $prebuiltDir 'manifest.json'
$sourceDll = Join-Path $prebuiltDir 'version.dll'
$wechatDir = [IO.Path]::GetFullPath($WeChatDirectory)
$exe = @((Join-Path $wechatDir 'Weixin.exe'), (Join-Path $wechatDir 'WeChat.exe')) |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1

if (-not $manifestPath -or -not (Test-Path -LiteralPath $manifestPath)) { throw 'prebuilt/manifest.json not found.' }
if (-not (Test-Path -LiteralPath $sourceDll)) { throw 'Run windows-agent\prepare-prebuilt-hook.ps1 first.' }
if (-not $exe) { throw "Weixin.exe/WeChat.exe not found under: $wechatDir" }

$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.targetWeChatVersion -ne '4.1.10.27') { throw 'Prebuilt manifest target version mismatch.' }
$dllHash = (Get-FileHash -LiteralPath $sourceDll -Algorithm SHA256).Hash.ToLowerInvariant()
if ($dllHash -ne $manifest.sha256) { throw "Prebuilt DLL hash mismatch. expected=$($manifest.sha256) actual=$dllHash" }

$version = (Get-Item -LiteralPath $exe).VersionInfo.FileVersion
if ($version -ne '4.1.10.27') { throw "WeChat version mismatch: $version, expected 4.1.10.27." }
$signature = Get-AuthenticodeSignature -LiteralPath $exe
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Tencent') {
    throw 'Weixin.exe signature is not a valid Tencent signature.'
}

$running = @(Get-CimInstance Win32_Process -Filter "Name='Weixin.exe' OR Name='WeChat.exe'" |
    Where-Object { $_.ExecutablePath -eq $exe })
if ($running.Count -gt 0) { throw 'Matching WeChat is running. Exit it from the tray before installing the Hook.' }

$destDll = Join-Path $wechatDir 'version.dll'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (Test-Path -LiteralPath $destDll) {
    Copy-Item -LiteralPath $destDll -Destination (Join-Path $wechatDir "version.dll.wechatbot-backup-$stamp") -Force
}
Copy-Item -LiteralPath $sourceDll -Destination $destDll -Force

$tokenFile = Join-Path $PSScriptRoot 'state\hook\native-token.txt'
$destToken = Join-Path $wechatDir 'wechatagent-hook.token'
if (Test-Path -LiteralPath $tokenFile) {
    if (Test-Path -LiteralPath $destToken) {
        Copy-Item -LiteralPath $destToken -Destination (Join-Path $wechatDir "wechatagent-hook.token.wechatbot-backup-$stamp") -Force
    }
    Copy-Item -LiteralPath $tokenFile -Destination $destToken -Force
}

if ($ConfigureLoopbackFirewall) {
    $ruleName = "WeChatBot Hook $Port loopback"
    $existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    if ($existing) { Remove-NetFirewallRule -DisplayName $ruleName }
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP `
        -LocalPort $Port -RemoteAddress '127.0.0.1','::1' -Profile Any | Out-Null
    Write-Output "Firewall allow rule created for loopback only: $ruleName"
}

@{
    version = '4.1.10.27'
    protocol = 'UPSTREAM_PREBUILT'
    source = $manifest.sourceUrl
    sha256 = $dllHash
    authenticode = $manifest.authenticode
    port = $Port
    installedAt = (Get-Date).ToString('o')
    directory = $wechatDir
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $wechatDir 'wechatbot-hook-install.json') -Encoding UTF8

Write-Output "Prebuilt Hook installed into $wechatDir"
Write-Output "DLL SHA-256 verified: $dllHash"
Write-Output "The upstream DLL is unsigned; trust is anchored to the pinned release hash in prebuilt/manifest.json."
if (-not $NoLaunch) {
    Start-Process -FilePath $exe -ArgumentList "StartPort=$Port" -WorkingDirectory $wechatDir
    Write-Output "WeChat launched. After login, open /api/admin/wechat/hook or call /QueryDB/status on 127.0.0.1:$Port."
}
