# opencode on Android

Latest release: **[v1.4](https://github.com/mhzsajan/opencode-android/releases/tag/v1.4)** —
standalone app works on-device (DNS fix), plus the small Termux variant.

Three Android apps that give [opencode](https://github.com/anomalyco/opencode) a phone-native UI.
All of them are thin WebView shells around `opencode serve`; they differ in where the server
comes from.

| | `octermux/` | `ocstandalone/` | `oclaunch/` |
|---|---|---|---|
| Package | `app.opencode.termux` | `app.opencode.standalone` | `app.opencode.launcher` |
| Server provided by | Termux (`RunCommandService`) | the app itself | Termux (`RunCommandService`) |
| Needs Termux installed | yes, configured once | **no** | yes |
| APK size | ~30 KB | ~62 MB | ~26 KB |
| Agent shell / git | yes (Termux's) | no (see below) | yes (Termux's) |
| Status | **the configuration that is known to work** | **works on-device, verified 2026-10-24** (chat included; free models need no key) | legacy, superseded by `octermux` |

`ocstandalone` now reaches the network and completes chat on the phone. `octermux` remains the
smaller option and still has Termux's bash/git for the agent. The long history of the standalone
networking bug is in [`docs/STANDALONE-NETWORK.md`](docs/STANDALONE-NETWORK.md).

## Terms

- **octermux** — WebView shell. On launch it asks Termux to run `~/.shortcuts/oc-server`, then
  waits for `http://127.0.0.1:4096` and loads the UI. It needs the Termux permission
  `com.termux.permission.RUN_COMMAND` and `allow-external-apps=true` in Termux.
- **ocstandalone** — bundles a glibc loader, a minimal glibc runtime and the opencode binary
  (~181 MB, deflated to ~60 MB in the APK), extracts them on first launch and supervises the
  server from a foreground service.
- **oclaunch** — the first version of the Termux path, kept for reference.

## Why the standalone app is not just a WebView

`opencode` ships as a **Bun-compiled glibc ELF** whose interpreter is
`/lib/ld-linux-aarch64.so.1`, which does not exist on Android. Running it outside Termux needs:

- a glibc loader plus `libc`, `libm`, `libpthread`, `libdl` — **3.8 MB** total
- the binary itself, launched as `ld.so opencode-bin serve ...` (never `patchelf` it; the Bun
  stub misbehaves when the ELF layout is rewritten)
- **no `LD_PRELOAD`**. Termux injects a bionic shim into its own processes that kills the glibc
  loader; a normal app has no such shim, so the environment is simply cleared.
- a resolver configuration: the bundled glibc looks for Termux's `resolv.conf`, which does not
  exist for an app. `build.ps1` patches that one path in the bundled `libc.so.6` and
  `ServerService` writes the phone's DNS servers into it before spawning the server. See
  `docs/STANDALONE-NETWORK.md`.

## Known limitations

- **No bash, no git** in `ocstandalone`. Termux's `bash`/`git` are bionic binaries and cannot run
  under the glibc loader, so they are not bundled. opencode's own file tools
  (read/write/edit/glob/grep) are built into the runtime and *do* work.
- **File access** in `ocstandalone` depends on the All-files-access grant (the setup screen has
  a row for it). Without it the project falls back to the app's own external directory.
- The first `ocstandalone` launch extracts ~180 MB, so expect a one-time wait with a progress
  bar.

## Build

Everything builds on Windows with the Android SDK's `aapt2`/`d8`/`apksigner` and a JDK 17+.
There is no Gradle wrapper: the scripts are a direct port of the original Termux pipeline.

Requirements:

- Android SDK with `build-tools` and at least one platform (`ANDROID_HOME` / `ANDROID_SDK_ROOT`)
- JDK 17+ (`JAVA_HOME`; Android Studio's JBR also works)
- a signing keystore. The scripts default to `ocstandalone/build/launcher.keystore`
  (gitignored). Pass `-Keystore <path>` or set `OC_KEYSTORE` to use another one.

### octermux (small, no payload)

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME    = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:KS_ALIAS='<alias>'; $env:KS_PASS='<password>'
powershell -ExecutionPolicy Bypass -File .\octermux\build.ps1
# -> octermux\build\opencode-termux.apk
```

Termux setup (once):

1. `termux.properties` → `allow-external-apps=true`, then restart Termux.
2. `~/.shortcuts/oc-server` — the server launcher script (a copy is on the test phone at
   `~/oc-server`; it guards with `flock` and `exec`s
   `~/.opencode/bin/opencode serve --port 4096`).
3. Install the APK, open it, grant the RUN_COMMAND permission.

### ocstandalone (heavy, bundles the server)

```powershell
# 1. restore the 181 MB runtime payload from an APK that already contains it
#    (the GitHub release asset, or a previous build) - it is not committed:
.\ocstandalone\stage-assets.ps1 -FromApk C:\path\opencode-android-v1.0.apk

# 2. sign with the keystore (restore your backup, or generate one - see below)
$env:KS_ALIAS='<alias>'; $env:KS_PASS='<password>'

# 3. build
powershell -ExecutionPolicy Bypass -File .\ocstandalone\build.ps1
# -> ocstandalone\build\opencode-solo.apk
```

`build.ps1` fails with instructions if the keystore or the payload is missing, and picks
`android.jar` from the newest installed platform. `stage-assets.ps1` needs only a zip reader,
so it also works when the source is a release APK.

Two steps in the Windows script matter for correctness:

- it **patches the bundled `libc.so.6`** so glibc reads `files/resolv.conf` instead of Termux's
  path (see `docs/STANDALONE-NETWORK.md`);
- it **adds assets and `classes.dex` itself** with explicit forward-slash entry names. `aapt2`
  with `-A` on Windows writes `assets/lib\libc.so.6` (backslash), which `AssetManager.open()`
  cannot find — that bug made the first run fail with `FileNotFoundException`.

> **Keystore**: Android will not install an update whose signature differs from the installed
> app. Keep the keystore outside the repo. If it is lost, the app has to be uninstalled before a
> new build can be installed.

## Install

```powershell
adb install -r ocstandalone\build\opencode-solo.apk
adb install -r octermux\build\opencode-termux.apk
```

On the phone itself (no adb), copy the APK to Download and open it; allow "install unknown
apps" for the file manager/browser you use.

## Debugging

```powershell
adb logcat -s ocstandalone:*     # embedded server output is mirrored here as "srv| ..."
adb logcat -s octermux:*         # this app only logs the ping/load cycle
```

The embedded server also writes `filesDir/home/server.log`, which is not readable over adb.

## Repository layout

```
octermux/       Termux-backed WebView shell (the working configuration)
ocstandalone/   self-contained app: payload staging, installer, server service, setup screen
oclaunch/       original Termux-backed shell, kept for reference
docs/           network investigation for the standalone app
```
