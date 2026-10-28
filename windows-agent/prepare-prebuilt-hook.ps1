param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot 'prebuilt\manifest.json'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'prebuilt')
)
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$outputDll = Join-Path $OutputDirectory $manifest.fileName
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

if (-not (Test-Path -LiteralPath $outputDll)) {
    Write-Output "Downloading $($manifest.sourceUrl)"
    & curl.exe --ssl-no-revoke -L --fail --retry 10 --retry-all-errors --retry-delay 2 `
        --continue-at - -o $outputDll $manifest.sourceUrl
    if ($LASTEXITCODE -ne 0) { throw "Download failed with exit code $LASTEXITCODE" }
}

$actualSize = (Get-Item -LiteralPath $outputDll).Length
$actualHash = (Get-FileHash -LiteralPath $outputDll -Algorithm SHA256).Hash.ToLowerInvariant()
if ($manifest.size -and $actualSize -ne [int64]$manifest.size) {
    throw "Size mismatch: expected=$($manifest.size) actual=$actualSize"
}
if ($actualHash -ne $manifest.sha256) {
    throw "SHA-256 mismatch: expected=$($manifest.sha256) actual=$actualHash"
}
Write-Output "Prebuilt Hook verified: $outputDll"
Write-Output "SHA-256: $actualHash"
