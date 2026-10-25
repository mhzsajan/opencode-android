# opencode on Android

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/mhzsajan/opencode-android)](https://github.com/mhzsajan/opencode-android/releases)
[![Contributing](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

Latest release: **[v1.6](https://github.com/mhzsajan/opencode-android/releases/tag/v1.6)** (pre-release) —
opencode **2.1.4** with a loopback auth proxy, in **two payload variants**: arm64 for phones and
x86_64 for the Android emulator. Both were verified end to end (chat and the agent's shell tool).
The previous **[v1.4](https://github.com/mhzsajan/opencode-android/releases/tag/v1.4)** (opencode
1.18.34) stays the stable fallback.

The two APKs share `applicationId` and `versionCode`, so install exactly one per device — switching
variants needs an uninstall first. Why the x86_64 build is a separate payload (musl, plus a ptrace
shim for the app's seccomp filter): [`docs/OPENCODE-V2.md`](docs/OPENCODE-V2.md) §6. Dev environment
(SDK/AVDs on `G:`, no arm64 emulator possible on this host):
[`docs/DEV-ENVIRONMENT.md`](docs/DEV-ENVIRONMENT.md).

Three Android apps that give [opencode](https://github.com/anomalyco/opencode) a phone-native UI.
All of them are thin WebView shells around `opencode serve`; they differ in where the server
comes from.

| | `octermux/` | `ocstandalone/` | `oclaunch/` |
|---|---|---|---|
| Package | `app.opencode.termux` | `app.opencode.standalone` | `app.opencode.launcher` |
| Server provided by | Termux (`RunCommandService`) | the app itself | Termux (`RunCommandService`) |
| Needs Termux installed | yes, configured once | **no** | yes |
| APK size | ~30 KB | ~114 MB | ~26 KB |
| Agent shell / git | yes (Termux's) | **yes — shell tool runs, inside the app sandbox** (verified 2026-10-24); no system git | yes (Termux's) |
| Status | **the configuration that is known to work** | **works on-device and on the x86_64 emulator** (chat included; free models need no key) | legacy, superseded by `octermux` |

`ocstandalone` now reaches the network, completes chat, and runs the agent's shell tool — as the
app's own uid, inside the sandbox. `octermux` remains the smaller option and still has Termux's
bash/git for the agent. The long history of the standalone networking bug is in
[`docs/STANDALONE-NETWORK.md`](docs/STANDALONE-NETWORK.md).

## Terms

- **octermux** — WebView shell. On launch it asks Termux to run `~/.shortcuts/oc-server`, then
  waits for `http://127.0.0.1:4096` and loads the UI. It needs the Termux permission
  `com.termux.permission.RUN_COMMAND` and `allow-external-apps=true` in Termux.
- **ocstandalone** — bundles a loader, a minimal libc runtime and the opencode binary
  (~236 MB arm64, ~239 MB x86_64; deflated to ~114 MB in the APK), extracts them on first launch
  and supervises the server from a foreground service. The x86_64 payload additionally ships a
  small ptrace shim (built from [`shim/shim.c`](shim/shim.c)) because this app's `targetSdk 28`
  seccomp filter traps syscalls a musl build issues.
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

Two payload variants exist; stage the one you need (payloads are not committed — each binary is
over GitHub's 100 MB per-file limit).

```powershell
# 1a. arm64 payload (phones): binary from npm, glibc runtime from a released APK
powershell -ExecutionPolicy Bypass -File .\ocstandalone\stage-assets-v2.ps1 `
    -FromApk C:\path\opencode-solo.apk

# 1b. -or- x86_64 payload (emulator): musl binary from npm, runtime from the v1.6 x64 APK,
#     plus the shim compiled from shim\shim.c
powershell -ExecutionPolicy Bypass -File .\ocstandalone\stage-assets-x64.ps1 `
    -FromApk C:\path\opencode-solo-x64.apk

# 2. sign with the keystore (restore your backup, or generate one - see below)
$env:KS_ALIAS='<alias>'; $env:KS_PASS='<password>'

# 3. build (omit -Arch for arm64)
powershell -ExecutionPolicy Bypass -File .\ocstandalone\build.ps1 -Arch x64
# -> ocstandalone\build\opencode-solo.apk      (arm64)
# -> ocstandalone\build\opencode-solo-x64.apk  (x86_64)
```

`build.ps1` fails with instructions if the keystore or the payload is missing, and picks
`android.jar` from the newest installed platform. Both staging scripts need only a zip reader for
the runtime libs, so a release APK is a valid source. The x64 build also rebuilds the shim if
`assets\x64\shim` is absent — only `shim\shim.c` is tracked.

Two steps in the Windows script matter for correctness:

- it **patches the bundled libc's resolver path** so it reads an app-writable file instead of
  Termux's path (arm64) or `/etc/resolv.conf` (x64) — see `docs/STANDALONE-NETWORK.md`;
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

## License & contributing

This project is released under the [MIT License](LICENSE).

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md). Please open an issue before starting
anything larger than a small fix, and read `HANDOFF.md` / `docs/OPENCODE-V2.md`
first; most "obvious" fixes have already been tried.

## Repository layout

```
octermux/       Termux-backed WebView shell (the working configuration)
ocstandalone/   self-contained app: payload staging, installer, server service, setup screen
oclaunch/       original Termux-backed shell, kept for reference
docs/           network investigation for the standalone app
```
