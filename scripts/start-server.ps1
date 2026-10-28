$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'load-env.ps1') -Root $root

if (-not $env:POSTGRES_PORT) { $env:POSTGRES_PORT = '5432' }
if (-not $env:POSTGRES_DB) { $env:POSTGRES_DB = 'wechat_bot' }
if (-not $env:POSTGRES_USER) { $env:POSTGRES_USER = 'wechat' }
if (-not $env:POSTGRES_PASSWORD) { $env:POSTGRES_PASSWORD = 'change-me-postgres' }
if (-not $env:SERVER_PORT) { $env:SERVER_PORT = '8080' }
if (-not $env:ADMIN_TOKEN) { $env:ADMIN_TOKEN = 'dev-admin-token' }
if (-not $env:AGENT_TOKEN) { $env:AGENT_TOKEN = 'dev-agent-token' }
if (-not $env:APP_SECRET_KEY) { $env:APP_SECRET_KEY = 'dev-secret-at-least-32-characters-long' }

$env:DB_URL = "jdbc:postgresql://localhost:$env:POSTGRES_PORT/$env:POSTGRES_DB"
$env:DB_USERNAME = $env:POSTGRES_USER
$env:DB_PASSWORD = $env:POSTGRES_PASSWORD

Push-Location $root
try {
    mvn -f server/pom.xml spring-boot:run
} finally {
    Pop-Location
}
