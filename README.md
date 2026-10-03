# opencode on Android

Two Android apps that give [opencode](https://github.com/anomalyco/opencode) a phone-native UI.
Both are thin WebView shells around `opencode serve`; they differ in where the server comes from.

| | `oclaunch/` | `ocstandalone/` |
|---|---|---|
| Package | `app.opencode.launcher` | `app.opencode.standalone` |
| Server provided by | Termux (`RunCommandService`) | the app itself |
| Needs Termux installed | yes | **no** |
| APK size | ~26 KB | ~60 MB |
| Agent shell / git | yes (Termux's) | no (see below) |

`ocstandalone` is the one to use. `oclaunch` is kept as a fallback and as the smaller-footprint option.

## Why the standalone app is not just a WebView

`opencode` ships as a **Bun-compiled glibc ELF** whose interpreter is
`/lib/ld-linux-aarch64.so.1`, which does not exist on Android. Running it outside Termux needs:

- a glibc loader plus `libc`, `libm`, `libpthread`, `libdl` — **3.8 MB** total
- the binary itself, launched as `ld.so opencode-bin serve ...` (never `patchelf` it; the Bun
  stub misbehaves when the ELF layout is rewritten)
- **no `LD_PRELOAD`**. Termux injects a bionic shim into its own processes that kills the glibc
  loader; a normal app has no such shim, so the environment is simply cleared.
- on Android 10/11 only, a SIGSYS shim, because the app sandbox traps `statx`. Android 12+ does
  not trap it, so the shim is not shipped.

## Known limitations

- **No bash, no git.** Termux's `bash`/`git` are bionic binaries and cannot run under the glibc
  loader, so they are not bundled. opencode's own file tools (read/write/edit/glob/grep) are built
  into the runtime and *do* work, so the agent can still edit real files.
- **File access** depends on the All-files-access grant. Without it the project falls back to the
  app's own external directory.
- The first launch extracts ~180 MB, so expect a one-time wait with a progress bar.

## Build

### Windows (primary, since 2026-10-03)

Needs an Android SDK (`ANDROID_HOME`, e.g. `G:\dev\android-sdk`) with `build-tools` and
at least one platform, plus a JDK 17+ (`JAVA_HOME`; Android Studio's JBR works too).

```powershell
# 1. restore the runtime payload (181 MB, not committed) from any APK that already
#    contains it - the GitHub release asset or a previous build:
.\ocstandalone\stage-assets.ps1 -FromApk C:\path\opencode-android-v1.0.apk

# 2. signing keystore (also not committed - keep a backup OUTSIDE the repo):
#    copy <backup>\standalone-launcher.keystore ocstandalone\build\launcher.keystore

# 3. build
$env:KS_ALIAS='<alias>'; $env:KS_PASS='<password>'
powershell -ExecutionPolicy Bypass -File .\ocstandalone\build.ps1
```

Output: `ocstandalone\build\opencode-solo.apk`. `build.ps1` fails with instructions if
the keystore or payload is missing, and picks `android.jar` from the SDK's newest
platform.

### Termux (legacy - the original pipeline, kept for reference)

Requires Termux with `aapt2`, `d8`, `apksigner`, `openjdk-21`, and a `android.jar`.

```sh
# 1. android.jar (27 MB) -- not committed
mkdir -p ~/build/sdk
curl -L -o ~/build/p35.zip https://dl.google.com/android/repository/platform-35_r01.zip
unzip -j ~/build/p35.zip 'android-*/android.jar' -d ~/build/sdk && rm ~/build/p35.zip

# 2. the runtime payload (181 MB) -- not committed, exceeds GitHub's 100 MB file limit
pkg install glibc-repo && pkg install glibc
./stage-assets.sh

# 3. sign. the keystore is not committed either
export KS_ALIAS=<alias>
export KS_PASS=<password>

./ocstandalone/build.sh
```

`build.sh` fails with instructions if `android.jar` or the payload is missing.

## Install

`rish`/Shizuku notes, all learned the hard way:

- `rish` on current Shizuku only runs commands **piped through stdin**. `rish "echo hi"` exits 0
  and prints nothing, which is indistinguishable from Shizuku being stopped.
- `pm install` cannot read FUSE paths. Copy to `/data/local/tmp` first, because `system_server`
  cannot read `/storage/emulated/0`:

  ```sh
  cp /storage/emulated/0/Download/app.apk /data/local/tmp/a.apk && chmod 644 /data/local/tmp/a.apk
  pm install -r /data/local/tmp/a.apk
  ```

- Without Shizuku, copy the APK to `~/storage/shared/Download/` and install it by hand.

## Debugging

```sh
# app logs (WebView console messages are mirrored here)
logcat -s ocstandalone

# the server's own output
cat /data/data/app.opencode.standalone/files/home/server.log
```
