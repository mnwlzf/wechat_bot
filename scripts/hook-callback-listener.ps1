param(
    [int]$Port = 18099,
    [string]$LogPath = "D:\wechat_bot\logs\hook-callback.log"
)

$ErrorActionPreference = 'Stop'
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
$listener.Start()

while ($true) {
    $client = $listener.AcceptTcpClient()
    try {
        $client.ReceiveTimeout = 3000
        $stream = $client.GetStream()
        $buffer = New-Object byte[] 65536
        $sb = New-Object System.Text.StringBuilder
        $deadline = (Get-Date).AddSeconds(3)
        while ((Get-Date) -lt $deadline) {
            if ($stream.DataAvailable) {
                $read = $stream.Read($buffer, 0, $buffer.Length)
                if ($read -le 0) { break }
                [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($buffer, 0, $read))
            } else {
                Start-Sleep -Milliseconds 50
            }
            if ($sb.ToString() -match "\r\n\r\n") {
                Start-Sleep -Milliseconds 200
                if (-not $stream.DataAvailable) { break }
            }
        }
        $raw = $sb.ToString()
        $line = "$(Get-Date -Format o) === request ===`r`n$raw`r`n"
        [System.IO.File]::AppendAllText($LogPath, $line, [System.Text.Encoding]::UTF8)
        $body = 'ok'
        $resp = "HTTP/1.1 200 OK`r`nContent-Type: text/plain`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n$body"
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($resp)
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
    } catch {
        [System.IO.File]::AppendAllText($LogPath, "$(Get-Date -Format o) error: $($_.Exception.Message)`r`n", [System.Text.Encoding]::UTF8)
    } finally {
        $client.Close()
    }
}
