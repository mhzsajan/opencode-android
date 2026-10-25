# Opencode on Android

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/mhzsajan/opencode-android)](https://github.com/mhzsajan/opencode-android/releases)
[![Contributing](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

Run [opencode](https://github.com/anomalyco/opencode) (an AI coding agent) on your Android
phone, with a phone-native UI. Two variants ship:

- **ocstandalone** (recommended) - self-contained app, no Termux needed. Bundles the opencode
  binary and a minimal libc runtime. ~114 MB APK. Works on phones (arm64) and the Android
  emulator (x86_64).
- **octermux** - thin WebView shell that drives a Termux-hosted opencode server. ~30 KB APK.
  Needs Termux installed and configured once.

## What it does

- Chat with a model (the built-in `opencode` provider's free model needs **no API key**).
- Run the agent's **shell tool** as the app's own user, inside the app sandbox.
- Browse and edit files on your phone (with the All-files-access grant).
- Sign in with an OpenCode account (device-code OAuth) or paste an API key.

## Download

Grab the latest APKs from the
[Releases page](https://github.com/mhzsajan/opencode-android/releases):

| File | Architecture | Use on |
|---|---|---|
| `opencode-solo.apk` | arm64 | Phones |
| `opencode-solo-x64.apk` | x86_64 | Android emulator |

Both are `versionCode 7` / `versionName 1.6`, `targetSdk 28`, not debuggable, and signed with the
same key, so install **exactly one per device** (switching variants needs an uninstall first).

The release is currently a pre-release; v1.4 (opencode 1.18.34) remains the stable fallback.

## Quick start (ocstandalone)

1. Install the APK for your architecture.
2. Open the app. The setup screen asks for permissions (all optional) and a provider key.
   - Free models work with no key. For paid models, tap "Sign in with OpenCode" or paste an
     `oc_sk_...` / `sk-...` key.
3. Tap "Start opencode". The first launch extracts the runtime (~180 MB, one-time).
4. The app opens a WebView onto the opencode UI. Chat, run the shell tool, edit files.

Reach the on-device server from a PC over adb:

```powershell
adb forward tcp:8099 tcp:4097
curl http://127.0.0.1:8099/api/info   # -> {"version":"2.1.4",...}
```

`4097` is the auth proxy (no credentials needed); `4096` is the server itself and answers 401.

## Build from source

Everything builds on Windows with the Android SDK's `aapt2`/`d8`/`apksigner` and JDK 17+.
There is no Gradle wrapper; the scripts are a direct port of the original Termux pipeline.

Requirements:

- Android SDK with `build-tools` and at least one platform (`ANDROID_HOME` / `ANDROID_SDK_ROOT`)
- JDK 17+ (`JAVA_HOME`)
- a signing keystore (gitignored; pass `-Keystore <path>` or set `OC_KEYSTORE`)

Payloads (the opencode binary + libc) are not committed (they exceed GitHub's 100 MB limit).
Restore them from a released APK:

```powershell
# arm64 (phones)
powershell -File .\ocstandalone\stage-assets-v2.ps1 -FromApk C:\path\opencode-solo.apk

# x86_64 (emulator)
powershell -File .\ocstandalone\stage-assets-x64.ps1 -FromApk C:\path\opencode-solo-x64.apk

$env:KS_ALIAS='<alias>'; $env:KS_PASS='<password>'
$env:ANDROID_HOME='G:\dev\android-sdk'; $env:GRADLE_USER_HOME='G:\dev\gradle-home'
powershell -ExecutionPolicy Bypass -File .\ocstandalone\build.ps1            # arm64
powershell -ExecutionPolicy Bypass -File .\ocstandalone\build.ps1 -Arch x64    # x86_64
```

### octermux (small, no payload)

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME    = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:KS_ALIAS='<alias>'; $env:KS_PASS='<password>'
powershell -ExecutionPolicy Bypass -File .\octermux\build.ps1
```

Termux setup (once): set `allow-external-apps=true` in `termux.properties`, restart Termux,
install `~/.shortcuts/oc-server`, install the APK, grant the RUN_COMMAND permission.

## Why two payloads

`opencode` ships as a Bun-compiled glibc ELF. Running it on Android needs:

- a glibc loader plus `libc`, `libm`, `libpthread`, `libdl` (about 3.8 MB)
- the binary itself, launched as `ld.so opencode-bin serve ...`
- a resolver configuration (the bundled glibc looks for Termux's `resolv.conf`)
- on x86_64: a ptrace shim, because this app's `targetSdk 28` seccomp filter traps
  syscalls that musl issues (`poll`, `epoll_wait`, `lstat`, `mkdir`, ...)

The x86_64 payload uses **musl** (not glibc): musl's compiled-in `/etc/resolv.conf` slot
is 16 bytes and can be rewritten to a relative `.resolv.conf`. See
[`docs/OPENCODE-V2.md`](docs/OPENCODE-V2.md) for the full seccomp and resolver write-up.

`targetSdk 28` is required so the app may `execve()` its own payload; that same targetSdk
brings the seccomp filter that makes the shim necessary.

## Known limitations

- **No bash, no git** in `ocstandalone`. Termux's bionic binaries cannot run under the glibc
  loader. opencode's own file tools (read/write/edit/glob/grep) are built in and do work.
- **File access** depends on the All-files-access grant (setup screen has a row for it).
- First launch extracts ~180 MB (one-time wait with a progress bar).
- `models.dev` may time out on slow links (5.9 MB catalogue vs a 10 s deadline). The built-in
  provider still answers.
- The WebSocket upgrade path through the auth proxy is untested (the UI uses SSE).

## Debugging

```powershell
adb logcat -s ocstandalone:*     # embedded server output is mirrored here as "srv| ..."
adb logcat -s octermux:*         # this app only logs the ping/load cycle
```

## Repository layout

```
octermux/       Termux-backed WebView shell
ocstandalone/   self-contained app: payload staging, installer, server service, setup screen
oclaunch/       original Termux-backed shell, kept for reference
shim/           ptrace shim source (x86_64 only)
docs/           network and v2 investigation notes
```

Older directories (`lyric-video/`, `lyric-video-remotion/`, `songtimer/`, `data/`,
`videosync-logo-overlay/`) are previous attempts and are not in use.

## License & contributing

This project is released under the [MIT License](LICENSE).

Contributions are welcome - see [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md). Please open an issue before starting anything
larger than a small fix, and read `docs/OPENCODE-V2.md` first; most "obvious" fixes
have already been tried.
