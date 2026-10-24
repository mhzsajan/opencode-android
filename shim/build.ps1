# build.ps1 - compile the x86_64 seccomp shim.
#
# The shim is freestanding static x86_64: it makes raw syscalls only, so no Linux
# toolchain or libc is involved and it can be produced on Windows and pushed to a
# device unchanged. shim.c is the only tracked artifact here; the binary lands in
# ocstandalone\assets\x64\shim, which ocstandalone\build.ps1 validates and packages
# (and which .gitignore excludes).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File shim\build.ps1
#   powershell -ExecutionPolicy Bypass -File shim\build.ps1 -Clang "C:\path\to\clang.exe"

param(
    [string]$Clang = ''
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$src = Join-Path $here 'shim.c'
$out = Join-Path (Join-Path (Split-Path $here -Parent) 'ocstandalone\assets\x64') 'shim'

if (-not (Test-Path $src)) { throw "source missing: $src" }

# --- locate clang ---------------------------------------------------------
# Order: -Clang, then CLANG, then PATH, then a per-user LLVM unpack. The last
# one is a glob rather than a fixed version so a clang upgrade does not break
# the build.
if (-not $Clang) { $Clang = $env:CLANG }
if (-not $Clang) {
    $onPath = Get-Command clang -ErrorAction SilentlyContinue
    if ($onPath) { $Clang = $onPath.Source }
}
if (-not $Clang) {
    $glob = Join-Path $env:USERPROFILE 'Tools\llvm\*\bin\clang.exe'
    $hit = @(Get-ChildItem $glob -ErrorAction SilentlyContinue | Sort-Object FullName -Descending)
    if ($hit.Count -gt 0) { $Clang = $hit[0].FullName }
}
if (-not $Clang -or -not (Test-Path $Clang)) {
    throw 'clang not found: pass -Clang <path>, set CLANG, or put clang on PATH'
}

# --- build ---------------------------------------------------------------
# -nostdlib/-ffreestanding: no libc, no crt. The entry point is _start in shim.c.
# --build-id=none keeps the output byte-identical between builds, so the SHA-256
# recorded in the docs stays meaningful.
$flags = @(
    '--target=x86_64-linux-gnu',
    '-nostdlib',
    '-static',
    '-ffreestanding',
    '-fno-stack-protector',
    '-O1',
    '-fuse-ld=lld',
    '-Wl,-e,_start',
    '-Wl,--build-id=none',
    '-o', $out,
    $src
)

New-Item -ItemType Directory -Force -Path (Split-Path $out -Parent) | Out-Null
& $Clang @flags
if ($LASTEXITCODE -ne 0) { throw "clang exited $LASTEXITCODE" }

# --- validate ------------------------------------------------------------
# A dynamically linked or wrong-architecture artifact would fail on the device
# as SIGSYS at launch, which looks exactly like the bug the shim exists to fix.
$b = [System.IO.File]::ReadAllBytes($out)
if ($b.Length -lt 64 -or $b[0] -ne 0x7f -or $b[1] -ne 0x45) { throw "not an ELF file: $out" }
$machine = $b[18] -bor ($b[19] -shl 8)
if ($machine -ne 0x3e) { throw ("not x86_64: e_machine=0x{0:x2}" -f $machine) }
if ($b[16] -ne 2) { throw ("not a static executable: e_type={0}" -f $b[16]) }
if ($b.Length -gt 65536) { throw "suspiciously large for a freestanding shim: $($b.Length) bytes" }

$sha = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($b)) -replace '-', ''
Write-Host "OK: $out"
Write-Host "    $($b.Length) bytes  x86_64 static  sha256=$sha"
