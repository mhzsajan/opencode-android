# build.ps1 - Windows build pipeline for opencode-solo.apk
# PowerShell port of build.sh (originally Termux-only), so releases no longer need a phone.
#
# Requirements:
#   - Android SDK with build-tools        (ANDROID_HOME or ANDROID_SDK_ROOT)
#   - JDK 17+                             (JAVA_HOME; falls back to Android Studio's JBR)
#   - staged payload: .\assets\arm64\  or  .\assets\x64\     (restore via stage-assets-v2.ps1 / stage-assets-x64.ps1)
#                                         each holding opencode-bin plus lib\*
#   - signing keystore: .\build\launcher.keystore              (keep a copy outside the repo!)
#
# Usage:
#   $env:KS_ALIAS='opencode'; $env:KS_PASS='<password>'
#   powershell -ExecutionPolicy Bypass -File build.ps1                 # arm64 -> opencode-solo.apk
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Arch x64       # x86_64 -> opencode-solo-x64.apk

param(
    [ValidateSet('arm64', 'x64')]
    [string]$Arch = 'arm64',
    [string]$AndroidJar = ''
)

$ErrorActionPreference = 'Stop'
$ROOT = $PSScriptRoot
$OUT = Join-Path $ROOT 'build'
$PAYLOAD = Join-Path (Join-Path $ROOT 'assets') $Arch
# Distinct output names: both variants share applicationId and versionCode, so
# keeping them apart by filename is what stops one overwriting the other in
# build\. Same versionCode also means neither can be installed over the other
# without an uninstall; that only matters when switching a single device.
$APK_NAME = 'opencode-solo.apk'
if ($Arch -eq 'x64') { $APK_NAME = 'opencode-solo-x64.apk' }

function Resolve-Env([string]$name) {
    $v = [Environment]::GetEnvironmentVariable($name)
    if (-not $v) { $v = [Environment]::GetEnvironmentVariable($name, 'User') }
    if (-not $v) { $v = [Environment]::GetEnvironmentVariable($name, 'Machine') }
    return $v
}

if (-not $env:KS_ALIAS) { throw 'set KS_ALIAS env var (keystore alias)' }
if (-not $env:KS_PASS)  { throw 'set KS_PASS env var (keystore password)' }
$KS_ALIAS = $env:KS_ALIAS
$KS_PASS = $env:KS_PASS

# --- SDK / JDK discovery -------------------------------------------------
$SDK = Resolve-Env 'ANDROID_HOME'
if (-not $SDK) { $SDK = Resolve-Env 'ANDROID_SDK_ROOT' }
if (-not $SDK -or -not (Test-Path $SDK)) { throw "Android SDK not found: set ANDROID_HOME" }

$JAVA = Resolve-Env 'JAVA_HOME'
if (-not $JAVA -or -not (Test-Path (Join-Path $JAVA 'bin\javac.exe'))) {
    $jbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path (Join-Path $jbr 'bin\javac.exe')) { $JAVA = $jbr }
    else { throw 'no JDK found (set JAVA_HOME)' }
}
$env:JAVA_HOME = $JAVA
$env:PATH = (Join-Path $JAVA 'bin') + ';' + $env:PATH

$btDirs = @(Get-ChildItem (Join-Path $SDK 'build-tools') -Directory | Sort-Object Name -Descending)
if ($btDirs.Count -eq 0) { throw "no build-tools installed in $SDK" }
$BT = $btDirs[0].FullName
$AAPT2 = Join-Path $BT 'aapt2.exe'
$D8 = Join-Path $BT 'd8.bat'
$APKSIGNER = Join-Path $BT 'apksigner.bat'
$ZIPALIGN = Join-Path $BT 'zipalign.exe'

# --- android.jar: repo sdk\ first, then the newest installed platform -----
if (-not $AndroidJar) {
    $repoJar = Join-Path (Split-Path $ROOT -Parent) 'sdk\android.jar'
    if (Test-Path $repoJar) { $AndroidJar = $repoJar }
}
if (-not $AndroidJar) {
    $plats = @(Get-ChildItem (Join-Path $SDK 'platforms') -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'android.jar') } |
        Sort-Object Name -Descending)
    if ($plats.Count -eq 0) { throw "no platforms installed in $SDK" }
    $AndroidJar = Join-Path $plats[0].FullName 'android.jar'
}
$AJ = (Resolve-Path $AndroidJar).Path

# --- preconditions --------------------------------------------------------
$KS = Join-Path $OUT 'launcher.keystore'
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
if (-not (Test-Path $KS)) {
    throw "keystore missing: $KS (restore standalone-launcher.keystore from your backup)"
}
if (-not (Test-Path (Join-Path $PAYLOAD 'opencode-bin'))) {
    throw "payload missing: $PAYLOAD\opencode-bin (run stage-assets-v2.ps1 for arm64, stage-assets-x64.ps1 for x64)"
}
if (-not (Test-Path (Join-Path $PAYLOAD 'lib'))) {
    throw "payload missing: $PAYLOAD\lib"
}
# The shim is only shipped with x86_64. Refuse a wrong-architecture or dynamic
# binary here: the failure it would cause is an app that dies with SIGSYS at
# launch, which looks identical to the original bug.
$shimPath = Join-Path $PAYLOAD 'shim'
# The binary is not tracked (.gitignore), so a fresh clone has only shim\shim.c.
# Build it rather than failing: the source and its exact flags ship with the
# repo, and the output is byte-identical between builds.
if ($Arch -eq 'x64' -and -not (Test-Path $shimPath)) {
    $shimBuild = Join-Path (Split-Path $ROOT -Parent) 'shim\build.ps1'
    if (-not (Test-Path $shimBuild)) { throw "shim missing and no builder: $shimBuild" }
    Write-Host "==> shim missing, building from shim\shim.c"
    & powershell -ExecutionPolicy Bypass -File $shimBuild
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $shimPath)) { throw "shim build failed: see $shimBuild" }
}
if (Test-Path $shimPath) {
    $sb = [System.IO.File]::ReadAllBytes($shimPath)
    if ($sb.Length -lt 64 -or $sb[0] -ne 0x7f -or $sb[1] -ne 0x45) {
        throw "shim is not an ELF file: $shimPath"
    }
    $machine = $sb[18] -bor ($sb[19] -shl 8)
    if ($machine -ne 0x3e) { throw ("shim is not x86_64 (e_machine=0x{0:x2})" -f $machine) }
    if ($Arch -ne 'x64') { throw "shim is only supported with -Arch x64" }
    Write-Host "==> shim: $($sb.Length) bytes, x86_64 static"
}

# --- patch the bundled libc resolver path ---------------------------------
# Each payload's libc has its resolver path compiled in. The staged payloads
# stay pristine: patch a copy under build\.
#
#   arm64 (Termux glibc, 53-byte slot)
#     /data/data/com.termux/files/usr/glibc/etc/resolv.conf
#       -> /data/data/app.opencode.standalone/files/resolv.conf   (52 chars)
#
#   x64 (musl's ld-musl-x86_64.so.1, 16-byte slot)
#     /etc/resolv.conf -> .resolv.conf   (relative, resolved against the child cwd)
#
# Without this res_init() finds no nameserver at all ("Failed to fetch models.dev
# ... Unable to connect"). The names must match ServerService.RESOLV_CONF /
# RESOLV_CONF_X64, and writeResolvConf() writes the file before each spawn.
if ($Arch -eq 'arm64') {
    $RESOLV_OLD = '/data/data/com.termux/files/usr/glibc/etc/resolv.conf'
    $RESOLV_NEW = '/data/data/app.opencode.standalone/files/resolv.conf'
} else {
    $RESOLV_OLD = '/etc/resolv.conf'
    $RESOLV_NEW = '.resolv.conf'
}

$PATCHED = Join-Path $OUT "payload-$Arch"
if (Test-Path $PATCHED) { Remove-Item $PATCHED -Recurse -Force }
New-Item -ItemType Directory -Force -Path $PATCHED | Out-Null
Copy-Item (Join-Path $PAYLOAD 'lib\*') $PATCHED -Force

# Which file holds the resolver path depends on the payload (glibc has it in
# libc.so.6; musl folds libc into ld-musl-*.so.1), so find it rather than
# naming it. Exactly one file must contain it - a silent no-op here is what
# makes every hostname lookup fail at runtime with no build-time error.
$patchedCount = 0
Get-ChildItem $PATCHED -File | ForEach-Object {
    $file = $_.FullName
    $bytes = [System.IO.File]::ReadAllBytes($file)
    $text = [System.Text.Encoding]::ASCII.GetString($bytes)
    if ($text.IndexOf($RESOLV_NEW + [char]0) -ge 0) {
        Write-Host "==> $($_.Name): resolver path already patched ($Arch)"
        $patchedCount++
        return
    }
    $pos = $text.IndexOf($RESOLV_OLD)
    if ($pos -lt 0) { return }
    if ($RESOLV_NEW.Length -gt $RESOLV_OLD.Length) {
        throw "$($_.Name): replacement resolver path ($($RESOLV_NEW.Length)) does not fit the original $($RESOLV_OLD.Length)-byte slot"
    }
    $newBytes = [System.Text.Encoding]::ASCII.GetBytes($RESOLV_NEW)
    [Array]::Copy($newBytes, 0, $bytes, $pos, $newBytes.Length)
    # NUL-fill the rest of the slot, stopping before the old terminator
    for ($i = $pos + $newBytes.Length; $i -lt $pos + $RESOLV_OLD.Length; $i++) { $bytes[$i] = 0 }
    [System.IO.File]::WriteAllBytes($file, $bytes)
    Write-Host "==> patched resolver path in $($_.Name) ($Arch, offset $pos): $RESOLV_NEW"
    $patchedCount++
}
if ($patchedCount -eq 0) {
    throw "no payload file contains '$RESOLV_OLD' ($Arch) - the payload changed?"
}

# --- clean ----------------------------------------------------------------
# Only this run's outputs. This used to remove both APK names, so building one
# architecture silently deleted the other variant's finished artifact — the
# second build of a two-variant release left exactly one file behind.
foreach ($d in @('gen', 'classes', 'dex')) {
    $p = Join-Path $OUT $d
    if (Test-Path $p) { Remove-Item $p -Recurse -Force }
}
foreach ($f in @('res.zip', 'base.apk', 'unsigned.apk', 'aligned.apk', $APK_NAME, "$APK_NAME.idsig")) {
    $p = Join-Path $OUT $f
    if (Test-Path $p) { Remove-Item $p -Force }
}

Write-Host '==> aapt2 compile'
$aa = @('compile', '--dir', (Join-Path $ROOT 'res'), '-o', (Join-Path $OUT 'res.zip'))
& $AAPT2 @aa
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '==> aapt2 link'
# NOTE: assets are not passed with -A. aapt2 (at least the 36.0.0 Windows build)
# stores nested asset paths with BACKSLASHES ("assets/lib\libc.so.6"), which
# AssetManager.open("lib/libc.so.6") cannot find - the app failed with
# FileNotFoundException on first run. The packaging step below adds the assets
# itself with correct forward-slash entry names.
$al = @('link', '-o', (Join-Path $OUT 'base.apk'), '-I', $AJ,
    '--manifest', (Join-Path $ROOT 'AndroidManifest.xml'),
    '--java', (Join-Path $OUT 'gen'),
    '--min-sdk-version', '24', '--target-sdk-version', '28',
    '--auto-add-overlay', (Join-Path $OUT 'res.zip'))
& $AAPT2 @al
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '==> javac'
$sources = @(Get-ChildItem (Join-Path $ROOT 'src\app\opencode\standalone\*.java')).FullName
$sources += Join-Path $OUT 'gen\app\opencode\standalone\R.java'
$jc = @('-classpath', $AJ, '-source', '8', '-target', '8', '-nowarn', '-d', (Join-Path $OUT 'classes')) + $sources
& (Join-Path $JAVA 'bin\javac.exe') @jc
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '==> d8'
New-Item -ItemType Directory -Force -Path (Join-Path $OUT 'dex') | Out-Null
$classes = @(Get-ChildItem (Join-Path $OUT 'classes\app\opencode\standalone\*.class')).FullName
$d8a = @('--lib', $AJ, '--min-api', '24', '--output', (Join-Path $OUT 'dex')) + $classes
& $D8 @d8a
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '==> package (resources + assets + classes.dex)'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$unsigned = Join-Path $OUT 'unsigned.apk'
$aligned = Join-Path $OUT 'aligned.apk'
foreach ($p in @($unsigned, $aligned)) { if (Test-Path $p) { Remove-Item $p -Force } }

$srcZip = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $OUT 'base.apk'))
$outFs = [System.IO.File]::Create($unsigned)
try {
    $dst = New-Object System.IO.Compression.ZipArchive($outFs, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $copy = {
            param($entry, $name)
            $ne = $dst.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
            $in = $entry; $out = $ne.Open()
            try { $in.CopyTo($out) } finally { $out.Dispose() }
        }
        foreach ($e in $srcZip.Entries) {
            $in = $e.Open()
            try { & $copy $in $e.FullName } finally { $in.Dispose() }
        }
        # bundled runtime payload: asset entries must use '/' separators.
        # Exactly opencode-bin + lib/* end up in the apk, so Installer's
        # assets.list("lib") sees only the payload this variant carries.
        $in = [System.IO.File]::OpenRead((Join-Path $PAYLOAD 'opencode-bin'))
        try { & $copy $in 'assets/opencode-bin' } finally { $in.Dispose() }
        Get-ChildItem $PATCHED -File | ForEach-Object {
            $in = [System.IO.File]::OpenRead($_.FullName)
            try { & $copy $in ('assets/lib/' + $_.Name) } finally { $in.Dispose() }
        }
        # Optional seccomp shim. Installer looks for assets/shim and, when it is
        # there, prefixes the launch command with it; absent (arm64) the payload
        # is exec'd directly.
        $shimSrc = Join-Path $PAYLOAD 'shim'
        if (Test-Path $shimSrc) {
            $in = [System.IO.File]::OpenRead($shimSrc)
            try { & $copy $in 'assets/shim' } finally { $in.Dispose() }
        }
        # classes.dex (replaces the jar step, which would rewrite the zip again)
        $dex = Join-Path $OUT 'dex\classes.dex'
        $in = [System.IO.File]::OpenRead($dex)
        try { & $copy $in 'classes.dex' } finally { $in.Dispose() }
    } finally { $dst.Dispose() }
} finally {
    $srcZip.Dispose()
    $outFs.Dispose()
}

Write-Host '==> zipalign'
& $ZIPALIGN -p -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '==> sign'
$APK = Join-Path $OUT $APK_NAME
$sg = @('sign', '--ks', $KS, '--ks-key-alias', $KS_ALIAS,
    '--ks-pass', "pass:$KS_PASS", '--key-pass', "pass:$KS_PASS",
    '--v4-signing-enabled', 'true',
    '--out', $APK,
    $aligned)
& $APKSIGNER @sg
if ($LASTEXITCODE -ne 0) { throw 'apksigner sign failed' }

Write-Host '==> verify'
& $APKSIGNER verify $APK
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }
$badging = & $AAPT2 dump badging $APK
Write-Host ($badging | Select-Object -First 1)
Get-Item $APK | ForEach-Object {
    Write-Host ('  {0}  {1:N0} bytes  ({2})' -f $_.Name, $_.Length, $Arch)
}
Write-Host ('OK: ' + $APK)
