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

# --- clean ----------------------------------------------------------------
foreach ($d in @('gen', 'classes', 'dex')) {
    $p = Join-Path $OUT $d
    if (Test-Path $p) { Remove-Item $p -Recurse -Force }
}
foreach ($f in @('res.zip', 'base.apk', 'unsigned.apk', 'opencode-solo.apk', 'opencode-solo.apk.idsig')) {
    $p = Join-Path $OUT $f
    if (Test-Path $p) { Remove-Item $p -Force }
}

Write-Host '==> aapt2 compile'
$aa = @('compile', '--dir', (Join-Path $ROOT 'res'), '-o', (Join-Path $OUT 'res.zip'))
& $AAPT2 @aa
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '==> aapt2 link'
$al = @('link', '-o', (Join-Path $OUT 'base.apk'), '-I', $AJ,
    '--manifest', (Join-Path $ROOT 'AndroidManifest.xml'),
    '--java', (Join-Path $OUT 'gen'),
    '--min-sdk-version', '24', '--target-sdk-version', '28',
    '-A', (Join-Path $ROOT 'assets'),
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

Write-Host '==> package'
Copy-Item (Join-Path $OUT 'base.apk') (Join-Path $OUT 'unsigned.apk') -Force
& (Join-Path $JAVA 'bin\jar.exe') uf (Join-Path $OUT 'unsigned.apk') -C (Join-Path $OUT 'dex') 'classes.dex'
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

Write-Host '==> sign'
$sg = @('sign', '--ks', $KS, '--ks-key-alias', $KS_ALIAS,
    '--ks-pass', "pass:$KS_PASS", '--key-pass', "pass:$KS_PASS",
    '--v4-signing-enabled', 'true',
    '--out', (Join-Path $OUT 'opencode-solo.apk'),
    (Join-Path $OUT 'unsigned.apk'))
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
