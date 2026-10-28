$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

# 所有配置都来自根目录 .env；数据库连接由 POSTGRES_HOST / POSTGRES_PORT /
# POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD 组成，无需手写 JDBC 连接串。
# .env 缺失或某个变量留空时，使用 application.yml 中的默认值。
& (Join-Path $PSScriptRoot 'load-env.ps1') -Root $root

Push-Location $root
try {
    mvn -f server/pom.xml spring-boot:run
} finally {
    Pop-Location
}
