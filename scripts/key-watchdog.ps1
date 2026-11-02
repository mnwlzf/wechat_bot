# Optional watchdog: keep a hook installed so a re-login / account switch is
# captured automatically, without waiting for the backend to notice.
#
# It runs one capture attempt per Weixin.exe process id, then keeps watching for
# the next start (WeChat restart, log out and back in, ...).
#
# Install once with:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-key-watchdog.ps1
param(
    [string]$DllPath = '',
    [string]$KeyFile = '',
    [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here
if (-not $DllPath) { $DllPath = Join-Path $repo 'vendor\wxkey\wx_key.dll' }
if (-not $KeyFile) { $KeyFile = Join-Path $repo 'data\wechat-db-key.txt' }

$capture = Join-Path $here 'capture-wechat-db-key.ps1'
$handled = @{}

while ($true) {
    try {
        $procs = @(Get-CimInstance Win32_Process -Filter "Name='Weixin.exe'" -ErrorAction SilentlyContinue)
        if ($procs.Count -gt 0) {
            $ids = $procs | ForEach-Object { $_.ProcessId }
            $root = $procs | Where-Object { $ids -notcontains $_.ParentProcessId } | Select-Object -First 1
            if (-not $root) { $root = $procs | Select-Object -First 1 }
            $pidValue = [uint32]$root.ProcessId
            if (-not $handled.ContainsKey($pidValue)) {
                $handled[$pidValue] = $true
                Write-Output "$(Get-Date -Format s) hooking pid $pidValue"
                # -TimeoutSeconds 0: keep polling until the key shows up or WeChat exits
                & powershell -NoProfile -ExecutionPolicy Bypass -File $capture `
                    -DllPath $DllPath -KeyFile $KeyFile -TimeoutSeconds 0
            }
        }
    } catch {
        Write-Output "$(Get-Date -Format s) watchdog error: $($_.Exception.Message)"
    }
    Start-Sleep -Seconds 10
}
