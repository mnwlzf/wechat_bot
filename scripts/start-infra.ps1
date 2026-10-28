$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'load-env.ps1') -Root $root
docker compose --project-directory $root up -d
docker compose --project-directory $root ps
