param(
    [string]$Root = (Split-Path $PSScriptRoot -Parent)
)

# 从仓库根目录唯一的 .env 向当前进程注入环境变量。
# 规则：
#   - 忽略空行、整行注释（# 开头）、不含 = 的行
#   - 支持可选的 export 前缀与包裹用的单/双引号
#   - 值为空时视为未设置，交给 application.yml / vite.config.ts 的默认值
$envFile = Join-Path $Root '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    Write-Host "[load-env] 未找到 $envFile，将使用程序内置默认配置" -ForegroundColor Yellow
    return
}

Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim().TrimStart([char]0xFEFF)
    if (-not $line -or $line.StartsWith('#') -or -not $line.Contains('=')) { return }

    $parts = $line -split '=', 2
    $name = $parts[0].Trim()
    if ($name.StartsWith('export ')) { $name = $name.Substring(7).Trim() }

    $value = $parts[1].Trim()
    if ($value.Length -ge 2) {
        $first = $value[0]
        if (($first -eq '"' -and $value.EndsWith('"')) -or ($first -eq "'" -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
    }

    if (-not $name -or -not $value) { return }
    Set-Item -Path "Env:$name" -Value $value
}
