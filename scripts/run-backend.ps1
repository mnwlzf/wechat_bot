param(
    [int]$Port = 18080
)

$env:JAVA_HOME = "C:\Program Files\jdk-21.0.11"
Get-Content "D:\wechat_bot\.env" -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $name = $matches[1]
        $value = $matches[2]
        if ($value -ne '') {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}
$env:SERVER_PORT = $Port

& "C:\Program Files\jdk-21.0.11\bin\java.exe" -jar "D:\wechat_bot\server\target\wechat-bot-server-0.1.0.jar"
