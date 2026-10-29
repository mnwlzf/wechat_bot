param(
    [string]$TaskName = "WeChatBotGuard",
    [string]$GuardScript = "D:\wechat_bot\scripts\wechat-guard.ps1",
    [int]$Port = 30001,
    [string]$CallbackUrl = "http://127.0.0.1:18080/api/wechat/hook/callback",
    [string]$StatusPath = "D:\wechat_bot\logs\guard-status.json",
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'

if ($Uninstall) {
    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($existing) {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "removed scheduled task $TaskName"
    } else {
        Write-Host "scheduled task $TaskName not found"
    }
    return
}

if (-not (Test-Path $GuardScript)) {
    throw "guard script not found: $GuardScript"
}

$arguments = @(
    '-NoProfile',
    '-WindowStyle', 'Hidden',
    '-ExecutionPolicy', 'Bypass',
    '-File', "`"$GuardScript`"",
    '-Port', $Port,
    '-CallbackUrl', "`"$CallbackUrl`"",
    '-StatusPath', "`"$StatusPath`""
) -join ' '

$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero)
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Description 'Keeps WeChat running with the hook and probes port 30001' -Force | Out-Null

Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Seconds 3

$task = Get-ScheduledTask -TaskName $TaskName
$info = Get-ScheduledTaskInfo -TaskName $TaskName
Write-Host ("task={0} state={1} lastResult={2}" -f $task.TaskName, $task.State, $info.LastTaskResult)
