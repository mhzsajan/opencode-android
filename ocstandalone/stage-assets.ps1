# stage-assets.ps1 - restores ocstandalone/assets/ on Windows
# Windows equivalent of stage-assets.sh (Termux): the two pieces too large to commit are
#   1. the opencode binary (~181 MB uncompressed)
#   2. a minimal glibc runtime (lib/) to run it on Android
# Both live inside any built APK, so the simplest source is an existing APK - e.g. the
# GitHub release asset or a previous build. (stage-assets.sh could also download the
# official anomalyco/opencode arm64 release; here we just unpack an APK.)
#
# Usage:
#   .\stage-assets.ps1 -FromApk C:\path\opencode-android-v1.0.apk
#   .\stage-assets.ps1 -FromApk ... -Force     (re-stage over existing assets)

param(
    [Parameter(Mandatory = $true)][string]$FromApk,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$ASSETS = Join-Path $PSScriptRoot 'assets'

if ((Test-Path (Join-Path $ASSETS 'opencode-bin')) -and -not $Force) {
    Write-Host 'assets already staged (use -Force to overwrite)'
    exit 0
}
if (-not (Test-Path $FromApk)) { throw "apk not found: $FromApk" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$tmp = Join-Path $env:TEMP ('ocstage-' + [guid]::NewGuid().ToString('N'))
[System.IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $FromApk).Path, $tmp)
try {
    $src = Join-Path $tmp 'assets'
    if (-not (Test-Path (Join-Path $src 'opencode-bin'))) {
        throw "no assets/opencode-bin inside $FromApk - is this an opencode-solo apk?"
    }
    New-Item -ItemType Directory -Force -Path $ASSETS | Out-Null
    Copy-Item (Join-Path $src '*') $ASSETS -Recurse -Force
    Write-Host 'assets staged:'
    Get-ChildItem $ASSETS -Recurse -File | ForEach-Object {
        Write-Host ('  {0,12:N0}  {1}' -f $_.Length, $_.FullName.Substring($ASSETS.Length + 1))
    }
}
finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
