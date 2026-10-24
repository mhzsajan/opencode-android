# stage-assets-x64.ps1 - stage the opencode **v2** x86_64 payload on Windows
#
# This is the emulator payload. It is a different build from the arm64 one:
# @opencode/cli-linux-x64-musl@2.1.4 is linked against **musl**, not glibc,
# because musl's compiled-in resolver path is short enough to rewrite:
#
#   /etc/resolv.conf  ->  .resolv.conf   (relative; resolved against the cwd)
#
# The payload is three parts:
#   assets/x64/opencode-bin          from npm (Bun single-file, x86_64, dynamic)
#   assets/x64/lib/ld-musl-x86_64.so.1, libstdc++.so.6, libgcc_s.so.1
#                                    Termux's x86_64 musl + libstdc++ packages.
#                                    A released APK is the simplest carrier.
#   assets/x64/shim                  compiled from ..\shim\shim.c
#                                    (applied by this script, not npm)
#
# NOTE: the binary NEEDs libc.musl-x86_64.so.1, which in Termux is a symlink to
# the loader. The APK cannot carry a symlink, and does not need to - musl's
# loader satisfies that NEEDED entry itself.
#
# Usage:
#   .\stage-assets-x64.ps1                                  # uses ..\build\opencode-solo-x64.apk
#   .\stage-assets-x64.ps1 -FromApk C:\path\opencode-solo-x64.apk
#   .\stage-assets-x64.ps1 -LibDir  C:\path\to\lib          # already-extracted libs
#   .\stage-assets-x64.ps1 -Version 2.1.4
#
# Then: $env:KS_ALIAS='opencode'; $env:KS_PASS='opencode'
#       powershell -ExecutionPolicy Bypass -File .\build.ps1 -Arch x64

param(
    [string]$Version = '2.1.4',
    [string]$FromApk = '',
    [string]$LibDir = ''
)

$ErrorActionPreference = 'Stop'
$ROOT = $PSScriptRoot
$ASSETS = Join-Path (Join-Path $ROOT 'assets') 'x64'
$LIB = Join-Path $ASSETS 'lib'

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { throw 'npm not found (install Node.js)' }
if (-not (Get-Command tar -ErrorAction SilentlyContinue)) { throw 'tar not found (Windows 10+ ships one)' }
New-Item -ItemType Directory -Force -Path $ASSETS | Out-Null

function Test-Loader([string]$dir) {
    foreach ($n in @('ld-musl-x86_64.so.1', 'ld-linux-x86-64.so.2')) {
        if (Test-Path (Join-Path $dir $n)) { return $n }
    }
    return $null
}

# npm writes progress notices to stderr, and under ErrorActionPreference=Stop
# PowerShell turns each native stderr line into a terminating error. Run it with
# Continue, capture both streams, and pick the .tgz line out of the output.
function Invoke-NpmPack([string]$pkg, [string]$work) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        Push-Location $work
        try {
            $out = @(& npm pack --loglevel=error $pkg 2>&1 | ForEach-Object { "$_" })
        } finally {
            Pop-Location
        }
    } finally {
        $ErrorActionPreference = $prev
    }
    $tgz = $out | Where-Object { $_ -match '\.tgz\s*$' } | Select-Object -Last 1
    if (-not $tgz -or -not (Test-Path (Join-Path $work $tgz))) {
        throw ("npm pack $pkg failed:" + [Environment]::NewLine + ($out -join [Environment]::NewLine))
    }
    return (Join-Path $work $tgz)
}

# --- 1. runtime libs -------------------------------------------------------
if (Test-Loader $LIB) {
    Write-Host '==> assets/x64/lib already staged'
} elseif ($LibDir) {
    if (-not (Test-Path $LibDir)) { throw "LibDir not found: $LibDir" }
    if (-not (Test-Loader $LibDir)) { throw "LibDir has no x86_64 loader: $LibDir" }
    Write-Host "==> copying runtime from $LibDir"
    New-Item -ItemType Directory -Force -Path $LIB | Out-Null
    Copy-Item (Join-Path $LibDir '*') $LIB -Recurse -Force
} else {
    if (-not $FromApk) {
        $guess = Join-Path (Join-Path $ROOT 'build') 'opencode-solo-x64.apk'
        if (Test-Path $guess) { $FromApk = $guess }
    }
    if (-not $FromApk) {
        throw 'no runtime libs. Pass -FromApk <x64 APK> (e.g. the v1.6 release asset) or -LibDir <dir>'
    }
    Write-Host "==> extracting runtime from $FromApk"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $tmp = Join-Path $env:TEMP ('ocx64-' + [guid]::NewGuid().ToString('N'))
    try {
        [System.IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $FromApk).Path, $tmp)
        $src = Join-Path $tmp 'assets\x64\lib'
        if (-not (Test-Path $src)) { $src = Join-Path $tmp 'assets\lib' }
        if (-not (Test-Path $src)) { throw "no runtime libs inside $FromApk" }
        if (-not (Test-Loader $src)) { throw "no x86_64 loader inside $FromApk" }
        New-Item -ItemType Directory -Force -Path $LIB | Out-Null
        Copy-Item (Join-Path $src '*') $LIB -Recurse -Force
    } finally {
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
if (-not (Test-Loader $LIB)) { throw "no x86_64 loader in $LIB" }
Write-Host ('    libs: ' + ((Get-ChildItem $LIB -File).Name -join ', '))

# --- 2. opencode v2 binary from npm ---------------------------------------
$work = Join-Path $env:TEMP ('ocx64npm-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
try {
    Push-Location $work
    try {
        $pkg = "@opencode/cli-linux-x64-musl@$Version"
        Write-Host "==> npm pack $pkg"
        $tgz = Invoke-NpmPack $pkg $work
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
        $machine = $bytes[18] -bor ($bytes[19] -shl 8)
        if ($machine -ne 0x3e) { throw ("wrong architecture: e_machine=0x{0:x2}, expected x86_64 (0x3e)" -f $machine) }

        Copy-Item $bin (Join-Path $ASSETS 'opencode-bin') -Force
        Write-Host "==> staged opencode-bin ($($bytes.Length) bytes, x86_64)"
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}

# --- 3. seccomp shim ------------------------------------------------------
$shimBuild = Join-Path (Split-Path $ROOT -Parent) 'shim\build.ps1'
if (-not (Test-Path $shimBuild)) { throw "shim builder missing: $shimBuild" }
Write-Host '==> building shim'
& powershell -ExecutionPolicy Bypass -File $shimBuild
if ($LASTEXITCODE -ne 0) { throw "shim build failed: see $shimBuild" }

Write-Host 'assets staged:'
Get-ChildItem $ASSETS -Recurse -File | ForEach-Object {
    Write-Host ('  {0,12:N0}  {1}' -f $_.Length, $_.FullName.Substring($ASSETS.Length + 1))
}
