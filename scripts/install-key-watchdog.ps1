# Register (or remove) the key watchdog as a logon scheduled task.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-key-watchdog.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-key-watchdog.ps1 -Remove
param([switch]$Remove)

$ErrorActionPreference = 'Stop'
$taskName = 'WeChatBot-KeyWatchdog'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here
$watchdog = Join-Path $here 'key-watchdog.ps1'

if ($Remove) {
    if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        Write-Output "removed task $taskName"
    } else {
        Write-Output "task $taskName not found"
    }
    exit 0
}

if (-not (Test-Path $watchdog)) { throw "missing $watchdog" }

$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$watchdog`"" `
    -WorkingDirectory $repo
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Settings $settings -Principal $principal -Force | Out-Null
Write-Output "registered task $taskName (runs at logon, working dir $repo)"
Write-Output "log: Get-ScheduledTaskInfo -TaskName $taskName"
