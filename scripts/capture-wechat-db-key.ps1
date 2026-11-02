# Capture the WeChat 4.x SQLCipher passphrase and write it to a key file.
#
# Usage (normally called by the backend, or by key-watchdog.ps1):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-wechat-db-key.ps1 `
#       -DllPath vendor\wxkey\wx_key.dll -KeyFile data\wechat-db-key.txt -TimeoutSeconds 180
#
# The key is only readable while WeChat opens its database, so the hook must be
# installed before the user logs in. -TimeoutSeconds 0 waits until the key shows
# up or WeChat exits (that is what key-watchdog.ps1 uses).
# Exit codes: 0 ok, 2 missing files, 3 WeChat not running / no local db, 4 no key.
param(
    [Parameter(Mandatory = $true)][string]$DllPath,
    [Parameter(Mandatory = $true)][string]$KeyFile,
    [string]$DbPath = '',
    [int]$TimeoutSeconds = 180,
    [uint32]$ProcessId = 0
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $here 'wxkey\WxKeyCapture.cs'

if (-not (Test-Path $source)) { Write-Output "RESULT=FAIL missing $source"; exit 2 }
if (-not (Test-Path $DllPath)) { Write-Output "RESULT=FAIL missing dll $DllPath"; exit 2 }

Add-Type -TypeDefinition ([IO.File]::ReadAllText($source)) -Language CSharp

if ($ProcessId -eq 0) {
    $procs = @(Get-CimInstance Win32_Process -Filter "Name='Weixin.exe'" -ErrorAction SilentlyContinue)
    if ($procs.Count -eq 0) { Write-Output 'RESULT=FAIL Weixin.exe is not running'; exit 3 }
    $ids = $procs | ForEach-Object { $_.ProcessId }
    # The hook must go on the root process; the children are render/utility processes.
    $root = $procs | Where-Object { $ids -notcontains $_.ParentProcessId } | Select-Object -First 1
    if (-not $root) { $root = $procs | Select-Object -First 1 }
    $ProcessId = [uint32]$root.ProcessId
}

if (-not $DbPath) {
    $root = Join-Path $env:USERPROFILE 'Documents\xwechat_files'
    if (Test-Path $root) {
        $DbPath = Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $media = Join-Path $_.FullName 'db_storage\message\media_0.db'
            $contact = Join-Path $_.FullName 'db_storage\contact\contact.db'
            if (Test-Path $media) { $media } elseif (Test-Path $contact) { $contact }
        } | Select-Object -First 1
    }
}
if (-not $DbPath) {
    Write-Output 'RESULT=FAIL no local WeChat database found (log in once first)'
    exit 3
}

Write-Output "pid=$ProcessId db=$DbPath timeout=$TimeoutSeconds"
$log = ''
$key = [WxKeyCapture]::Capture($DllPath, $ProcessId, $DbPath, $TimeoutSeconds, [ref]$log)
if ($log) { Write-Output $log.TrimEnd() }

if ($key) {
    $directory = Split-Path -Parent $KeyFile
    if ($directory -and -not (Test-Path $directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    [IO.File]::WriteAllText($KeyFile, $key + "`r`n", (New-Object System.Text.ASCIIEncoding))
    Write-Output "RESULT=OK key=$key"
    exit 0
}

Write-Output 'RESULT=FAIL no key captured (log out of WeChat, run this, then log back in)'
exit 4
