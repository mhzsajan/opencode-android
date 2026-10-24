# stage-assets-v2.ps1 - stage the opencode **v2** runtime payload on Windows
#
# opencode v2 is published on npm as @opencode/cli (latest 2.1.4). The CLI is a
# Bun-compiled glibc aarch64 binary, so it runs on Android exactly like the v1
# payload did: with the bundled glibc loader/libs in assets/lib.
#
# This script:
#   1. makes sure assets/lib/ exists (if missing, extract it from a v1 APK that
#      contains the glibc runtime - the v1.4 GitHub release asset works);
#   2. downloads @opencode/cli-linux-arm64@<Version> from npm and unpacks
#      package/bin/opencode into assets/opencode-bin.
#
# Usage:
#   .\stage-assets-v2.ps1                              # latest known 2.1.4
#   .\stage-assets-v2.ps1 -Version 2.1.4
#   .\stage-assets-v2.ps1 -FromApk C:\path\opencode-android-v1.4.apk
#
# Then: $env:KS_ALIAS='opencode'; $env:KS_PASS='opencode'
#       powershell -ExecutionPolicy Bypass -File .\build.ps1
#
# NOTE: the build script patches the bundled libc's resolver path, so the
# assets/lib/ files must be the Termux glibc ones, not the host's.

param(
    [string]$Version = '2.1.4',
    [string]$FromApk = ''
)

$ErrorActionPreference = 'Stop'
$ROOT = $PSScriptRoot
$ASSETS = Join-Path $ROOT 'assets'
$LIB = Join-Path $ASSETS 'lib'

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { throw 'npm not found (install Node.js)' }
if (-not (Get-Command tar -ErrorAction SilentlyContinue)) { throw 'tar not found (Windows 10+ ships one)' }

# --- 1. glibc runtime libs -------------------------------------------------
if (-not (Test-Path (Join-Path $LIB 'ld-linux-aarch64.so.1'))) {
    if (-not $FromApk) {
        throw "assets/lib/ missing. Pass -FromApk <v1 APK containing the glibc runtime> (e.g. the v1.4 release asset)"
    }
    Write-Host "==> extracting glibc runtime from $FromApk"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $tmp = Join-Path $env:TEMP ('ocv2-' + [guid]::NewGuid().ToString('N'))
    try {
        [System.IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $FromApk).Path, $tmp)
        $src = Join-Path $tmp 'assets\lib'
        if (-not (Test-Path $src)) { throw "no assets/lib inside $FromApk" }
        New-Item -ItemType Directory -Force -Path $LIB | Out-Null
        Copy-Item (Join-Path $src '*') $LIB -Recurse -Force
        Write-Host ('    libs: ' + ((Get-ChildItem $LIB -File).Name -join ', '))
    } finally {
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
} else {
    Write-Host '==> assets/lib already staged'
}

# --- 2. opencode v2 binary from npm ---------------------------------------
$work = Join-Path $env:TEMP ('ocv2npm-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
try {
    Push-Location $work
    try {
        $pkg = "@opencode/cli-linux-arm64@$Version"
        Write-Host "==> npm pack $pkg"
        $tgz = (& npm pack $pkg 2>&1 | Select-Object -Last 1).ToString().Trim()
        if (-not (Test-Path $tgz)) { throw "npm pack did not produce a tarball (output: $tgz)" }
        Write-Host "==> extracting $tgz"
        & tar -xzf $tgz
        if ($LASTEXITCODE -ne 0) { throw 'tar failed' }
        $bin = Join-Path $work 'package\bin\opencode'
        if (-not (Test-Path $bin)) { throw "package/bin/opencode missing in $tgz" }

        $bytes = [System.IO.File]::ReadAllBytes($bin)
        if ($bytes.Length -lt 1000000) { throw "unexpectedly small binary ($($bytes.Length) bytes)" }
        if ($bytes[0] -ne 0x7F -or $bytes[1] -ne 0x45 -or $bytes[2] -ne 0x4C -or $bytes[3] -ne 0x46) {
            throw 'not an ELF binary'
        }

        Copy-Item $bin (Join-Path $ASSETS 'opencode-bin') -Force
        $ver = & $bin --version 2>$null
        Write-Host "==> staged opencode-bin ($($bytes.Length) bytes; reports: $($ver -join ' '))"
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host 'assets staged:'
Get-ChildItem $ASSETS -Recurse -File | ForEach-Object {
    Write-Host ('  {0,12:N0}  {1}' -f $_.Length, $_.FullName.Substring($ASSETS.Length + 1))
}
