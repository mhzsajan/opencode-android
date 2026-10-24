# Android dev environment on this machine

Written 2026-10-24. Everything here was verified on this host; nothing is copied from a README.

The design rule: **Android Studio stays on `C:`; everything that grows moves to `G:`.** Studio is
a fixed ~3.3 GB install, while SDK packages, system images, AVD userdata and the Gradle cache grow
without bound.

## 1. Where things live

| What | Path | Size now |
|---|---|---|
| Android Studio (unchanged) | `C:\Program Files\Android\Android Studio` | 3.3 GB |
| SDK | `G:\dev\android-sdk` | 9 050 MB |
| AVDs (userdata, snapshots) | `G:\dev\android-avd` | 4 192 MB |
| Gradle cache / `GRADLE_USER_HOME` | `G:\dev\gradle-home` | 3 387 MB |
| Scratch projects | `G:\dev\projects` | — |
| Keystore backup | `G:\dev\keystores\launcher.keystore` | — |

Free space at time of writing: `C:` 231 GB, `G:` 2 577 GB.

## 2. Environment variables (user scope, set persistently)

```
ANDROID_HOME      G:\dev\android-sdk
ANDROID_SDK_ROOT  G:\dev\android-sdk
ANDROID_AVD_HOME  G:\dev\android-avd
GRADLE_USER_HOME  G:\dev\gradle-home
JAVA_HOME         C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot
```

`PATH` gained `%ANDROID_HOME%\emulator`, `%ANDROID_HOME%\platform-tools` and
`%ANDROID_HOME%\cmdline-tools\latest\bin`. A duplicate winget `platform-tools` entry was removed —
with two `adb.exe` on `PATH`, which one you get depends on ordering, and a stale one silently
talks to an old server.

**`ANDROID_AVD_HOME` is why `local.properties` and AVD paths are not in the profile.** Because the
AVD home is not the default `%USERPROFILE%\.android\avd`, anything that ignores that variable will
not see the AVDs.

## 3. Installed packages

```
platforms       android-35  android-36  android-36.1  android-37.0
build-tools     34.0.0  35.0.0  36.0.0  37.0.0
emulator        37.2.12
system-images   android-37.0/google_apis_playstore/x86_64   2 710 MB
                android-35/google_apis_playstore/x86_64      2 161 MB
                android-37.0/default/arm64-v8a               1 950 MB  (cannot boot, see §5)
```

`ocstandalone/build.ps1` uses **build-tools 36.0.0** and `platforms/android-37.0`.

## 4. AVDs

| AVD | ABI | API | State |
|---|---|---|---|
| `Pixel_10_API_37` | x86_64 | 37 (Android 17) | boots to launcher, Play Store works |
| `Pixel_10_API_35` | x86_64 | 35 | boots |
| `Pixel_10_ARM64_API_37` | arm64-v8a | 37 | **inert** — see §5 |

Tuned from the defaults: `hw.ramSize=4096`, `vm.heapSize=512`, `hw.keyboard=yes`,
`hw.gpu.enabled=yes`, `hw.cpu.ncore=6`, and `PlayStore.enabled=yes` in the `.ini` (a Play Store
image whose ini says otherwise boots without the store).

Launch with the emulator binary, **not** the new `android` CLI:

```powershell
G:\dev\android-sdk\emulator\emulator.exe -avd Pixel_10_API_37 -port 5554
```

The `android` CLI keeps its own device registry and does **not** list AVDs created under
`ANDROID_AVD_HOME`, so `android emulator list` looks empty even when everything is fine.
`emulator.exe -list-avds` and `avdmanager list avd` do see them.

## 5. An arm64 emulator cannot run on this host

The arm64 system image installs fine and `avdmanager` creates the AVD, but the emulator exits in
under two seconds:

```
FATAL | Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on x86_64 host.
        System image must match the host architecture.
```

There is no cross-architecture TCG fallback on Windows.

`libndk_translation` (present in the x86_64 images, which advertise `abilist64=x86_64,arm64-v8a`)
turns out to translate **more** than native libraries: the arm64 payload's loader and Bun both
start under it (`opencode v2.1.4`, `Bun v1.4.4 • Linux arm64`). It still cannot serve — Bun panics
with `Unexpected futex_wait() return code: -2 - ENOENT` — so it is a curiosity, not a route.

Consequence for this project: **an x86_64 payload variant is required for emulator work**, and
since 1.6 there is one (`opencode-solo-x64.apk`, musl + a ptrace shim). The arm64 artifact is
verified on the phone and needs no emulator. See `docs/OPENCODE-V2.md` §6–7.

## 6. Acceleration and graphics

- `emulator -accel-check` → `WHPX(10.0.26300) is installed and usable`. HAXM is not needed.
- GPU is the real **AMD Radeon RX 9060 XT**, exposed through **OpenGL ES 3.1** (not Vulkan-only).
  `hw.gpu.enabled=yes` with the default `gfxstream` backend works; a screenshot of the launcher
  confirms real rendering rather than a blank surface.
- Do not run five assemblers/emulators at once — see the OOM note in the root `AGENTS.md`.

## 7. Toolchain proof

A throwaway Compose project at `G:\dev\projects\HelloAndroid` built `assembleDebug` →
**BUILD SUCCESSFUL in 6m29s** with Gradle 9.1.0 + AGP 9 + Kotlin. Its `local.properties` must
contain the escaped path:

```properties
sdk.dir=G\:\\dev\\android-sdk
```

## 8. Everyday commands

```powershell
$adb = 'G:\dev\android-sdk\platform-tools\adb.exe'

& $adb devices -l
& $adb -s emulator-5554 install -r .\ocstandalone\build\opencode-solo-x64.apk
& $adb -s emulator-5554 logcat -s ocstandalone:*

# screenshot: write to the device, then pull (piping exec-out through '>' corrupts the PNG)
& $adb -s emulator-5554 shell screencap -p /sdcard/x.png
& $adb -s emulator-5554 pull /sdcard/x.png "$env:TEMP\x.png"

# reach the on-device server API from the PC: 4097 is the auth proxy and needs no credentials,
# 4096 is the server itself and answers 401 without HTTP Basic
& $adb -s emulator-5554 forward tcp:8099 tcp:4097
Invoke-WebRequest http://127.0.0.1:8099/api/info -UseBasicParsing
```

## 9. Traps

- **`ANDROID_HOME` pointing at a nonexistent path** was the real reason builds that relied on the
  variable failed. The SDK had been moved to `G:` while the variable still named it; nothing
  errored until a build tried to resolve it.
- **`android emulator list` is not the source of truth** for AVDs (§4).
- **`local.properties` needs escaped backslashes** (§7); an unescaped `G:\dev\...` is read as
  escape sequences.
- **Two `adb.exe` on `PATH`** silently shadow each other (§2).
- Ports **4096/4097** are used on-device (server, auth proxy). On the PC, `adb forward` maps them
  locally, so a stale forward can make a dead server look alive.
