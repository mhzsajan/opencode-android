# build.ps1 - Windows build pipeline for opencode-solo.apk
# PowerShell port of build.sh (originally Termux-only), so releases no longer need a phone.
#
# Requirements:
#   - Android SDK with build-tools        (ANDROID_HOME or ANDROID_SDK_ROOT)
#   - JDK 17+                             (JAVA_HOME; falls back to Android Studio's JBR)
#   - staged assets:  .\assets\opencode-bin + .\assets\lib\*   (restore via stage-assets.ps1)
#   - signing keystore: .\build\launcher.keystore              (keep a copy outside the repo!)
#
# Usage:
#   $env:KS_ALIAS='opencode'; $env:KS_PASS='opencode'
#   powershell -ExecutionPolicy Bypass -File build.ps1

param(
    [string]$AndroidJar = ''
)

$ErrorActionPreference = 'Stop'
$ROOT = $PSScriptRoot
$OUT = Join-Path $ROOT 'build'

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
if (-not (Test-Path (Join-Path $ROOT 'assets\opencode-bin'))) {
    throw "assets missing: run stage-assets.ps1 -FromApk <built-or-release apk>"
}

# --- patch the bundled glibc resolver path --------------------------------
# The payload is Termux's glibc build, so glibc's resolver path is compiled in as
# /data/data/com.termux/files/usr/glibc/etc/resolv.conf -- a directory that does
# not exist for this app, which leaves the child with no nameserver at all
# ("Failed to fetch models.dev ... Unable to connect"). Point that single string
# at a file this app owns; ServerService writes it before spawning the server.
# It must match ServerService.RESOLV_CONF and fit the original 53-byte slot.
$RESOLV_OLD = '/data/data/com.termux/files/usr/glibc/etc/resolv.conf'
$RESOLV_NEW = '/data/data/app.opencode.standalone/files/resolv.conf'
$libc = Join-Path (Join-Path $ROOT 'assets') 'lib\libc.so.6'
$libcBytes = [System.IO.File]::ReadAllBytes($libc)
$libcText = [System.Text.Encoding]::ASCII.GetString($libcBytes)
if ($libcText.IndexOf($RESOLV_NEW) -ge 0) {
    Write-Host '==> libc.so.6 resolver path already patched'
} else {
    $pos = $libcText.IndexOf($RESOLV_OLD)
    if ($pos -lt 0) { throw "resolver path not found in $libc (payload changed?)" }
    if ($RESOLV_NEW.Length -gt $RESOLV_OLD.Length) {
        throw 'replacement resolver path does not fit the original slot'
    }
    $newBytes = [System.Text.Encoding]::ASCII.GetBytes($RESOLV_NEW)
    [Array]::Copy($newBytes, 0, $libcBytes, $pos, $newBytes.Length)
    $libcBytes[$pos + $newBytes.Length] = 0
    [System.IO.File]::WriteAllBytes($libc, $libcBytes)
    Write-Host "==> patched resolver path in libc.so.6 (offset $pos)"
}

# --- clean ----------------------------------------------------------------
foreach ($d in @('gen', 'classes', 'dex')) {
    $p = Join-Path $OUT $d
    if (Test-Path $p) { Remove-Item $p -Recurse -Force }
}
foreach ($f in @('res.zip', 'base.apk', 'unsigned.apk', 'aligned.apk', 'opencode-solo.apk', 'opencode-solo.apk.idsig')) {
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
        # bundled runtime payload: asset entries must use '/' separators
        $assetsRoot = Join-Path $ROOT 'assets'
        Get-ChildItem $assetsRoot -Recurse -File | ForEach-Object {
            $rel = $_.FullName.Substring($assetsRoot.Length + 1).Replace('\', '/')
            $in = [System.IO.File]::OpenRead($_.FullName)
            try { & $copy $in ('assets/' + $rel) } finally { $in.Dispose() }
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
$sg = @('sign', '--ks', $KS, '--ks-key-alias', $KS_ALIAS,
    '--ks-pass', "pass:$KS_PASS", '--key-pass', "pass:$KS_PASS",
    '--v4-signing-enabled', 'true',
    '--out', (Join-Path $OUT 'opencode-solo.apk'),
    $aligned)
& $APKSIGNER @sg
if ($LASTEXITCODE -ne 0) { throw 'apksigner sign failed' }

Write-Host '==> verify'
& $APKSIGNER verify (Join-Path $OUT 'opencode-solo.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }
$badging = & $AAPT2 dump badging (Join-Path $OUT 'opencode-solo.apk')
Write-Host ($badging | Select-Object -First 1)
Get-Item (Join-Path $OUT 'opencode-solo.apk') | ForEach-Object {
    Write-Host ('  {0}  {1:N0} bytes' -f $_.Name, $_.Length)
}
Write-Host ('OK: ' + (Join-Path $OUT 'opencode-solo.apk'))
