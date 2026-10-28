$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $root 'web')
try {
    if (-not (Test-Path 'node_modules')) {
        npm install
    }
    npm run dev
} finally {
    Pop-Location
}
