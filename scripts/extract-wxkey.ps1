# Put wx_key.dll into vendor\wxkey\ (it is a third-party binary and is not
# committed). Lookup order:
#   1. an existing copy in vendor\wxkey\        -> nothing to do
#   2. a copy left by a previous capture         -> logs\key\wx_key.dll
#   3. extract from the WeChatAgent installer    -> D:\WeChatAgent-Setup-*.exe
param(
    [string]$Installer = '',
    [string]$SevenZip = ''
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here
$target = Join-Path $repo 'vendor\wxkey\wx_key.dll'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null

if (Test-Path $target) {
    Write-Output "already present: $target"
    exit 0
}

$fallback = Join-Path $repo 'logs\key\wx_key.dll'
if (Test-Path $fallback) {
    Copy-Item $fallback $target -Force
    Write-Output "copied from $fallback"
    exit 0
}

if (-not $Installer) {
    $Installer = Get-ChildItem -Path 'D:\','C:\Users\' -Filter 'WeChatAgent-Setup-*.exe' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Installer -or -not (Test-Path $Installer)) {
    Write-Output 'installer not found; copy wx_key.dll into vendor\wxkey\ manually'
    Write-Output '  (it lives inside the WeChatAgent installer: resources\project\components\weflow-cli\resources\key\win32\x64\wx_key.dll)'
    exit 2
}

if (-not $SevenZip) {
    foreach ($candidate in @(
        (Join-Path $repo 'logs\key\7za\7za.exe'),
        (Join-Path $env:ProgramFiles '7-Zip\7z.exe'),
        (Join-Path ${env:ProgramFiles(x86)} '7-Zip\7z.exe'))) {
        if ($candidate -and (Test-Path $candidate)) { $SevenZip = $candidate; break }
    }
}
if (-not $SevenZip) {
    Write-Output 'no 7-Zip found; put 7za.exe under logs\key\7za\ or install 7-Zip'
    exit 2
}

$temp = Join-Path $env:TEMP ('wxkey-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $temp | Out-Null
Write-Output "extracting from $Installer ..."
& $SevenZip x $Installer '-o'$temp '-y' 'resources\project\components\weflow-cli\resources\key\win32\x64\wx_key.dll' | Out-Null
$found = Get-ChildItem -Path $temp -Recurse -Filter 'wx_key.dll' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($found) {
    Copy-Item $found.FullName $target -Force
    Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Output "extracted -> $target"
    exit 0
}
Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue
Write-Output 'wx_key.dll not found inside the installer (wrong path or new installer layout)'
exit 2
