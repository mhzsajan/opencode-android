# opencode Android — session handoff

Updated **2026-10-25**. Read this first after any disconnect. The deep write-up of v2 (proxy,
payloads, seccomp shim, evidence) is [`docs/OPENCODE-V2.md`](docs/OPENCODE-V2.md); the machine
layout is [`docs/DEV-ENVIRONMENT.md`](docs/DEV-ENVIRONMENT.md).

---

## 1. TL;DR

| | State |
|---|---|
| `ocstandalone` v1.6 — `master` | **Released as pre-release [v1.6](https://github.com/mhzsajan/opencode-android/releases/tag/v1.6)**, opencode 2.1.4, **two payload variants**: arm64 for phones, x86_64 for the emulator. Both are `versionCode 7` / `versionName 1.6`, `targetSdk 28`, not debuggable. The x64 variant is verified end to end on `emulator-5554` — chat **and** the agent's shell tool. |
| `ocstandalone` v1.5 — tag `v1.5` | arm64-only predecessor (code 6). Superseded by v1.6; kept as a restore source for the glibc libs. |
| `ocstandalone` v1.4 — tag `v1.4` | opencode 1.18.34, the stable fallback. |
| `octermux` (Termux-backed, ~30 KB) | Builds clean, main-thread `ping()` bug fixed. Still **not tested on device** (the phone is gone and Termux has no opencode CLI). |
| `oclaunch` | Legacy, superseded by `octermux`. |

The two 1.6 artifacts:

| variant | file | bytes | SHA-256 | payload |
|---|---|---|---|---|
| arm64 | `opencode-solo.apk` | 114 751 180 | `518C86B212CE7C374AA2D2B9E8992962E4A648B1EE1CB314ED21ECD5139AE3FC` | aarch64 glibc (Termux), no shim |
| x86_64 | `opencode-solo-x64.apk` | 114 407 041 | `B1EAE1C9FC4B57D05750E28A0A8DBDE4AF50AEAC601E1AC34B385A9C51753428` | x86_64 musl, `assets/shim` |

Same `applicationId` **and** same `versionCode` → one per device; switching variants needs an
uninstall. Both signed by `84:E7:50:…:10:6C:31`.

**Honest scope of the arm64 proof:** the phone verified v1.5. The 1.6 arm64 APK is the same Java
(verified on x64) with the same payload, rebuilt at code 7 with `debuggable` removed — it has not
run on arm64 hardware, because there is none. The x64 APK *is* the tested binary: every zip entry
of the released file matched the installed one (length, compressed size, CRC32) at release time.

## 2. What changed in 1.6

1. **An x86_64 payload variant** — `@opencode/cli-linux-x64-musl@2.1.4` plus Termux's musl loader
   and `libstdc++`/`libgcc_s`. musl, not glibc: its compiled-in `/etc/resolv.conf` slot is 16 bytes
   and can be rewritten to the relative `.resolv.conf`; the Ubuntu-glibc plan is dead
   (`docs/OPENCODE-V2.md` §6.4).
2. **A ptrace shim, `shim/shim.c`** — this app's `targetSdk 28` seccomp filter `SECCOMP_RET_TRAP`s
   the pre-`*at` syscalls musl issues (`poll`, `epoll_wait`, `lstat`, `mkdir`, …). The shim replays
   each one as its allowed equivalent. 5 728 bytes, static x86_64, shipped **only** with the x64
   payload; `assets/shim` decides the launch path (`docs/OPENCODE-V2.md` §7).
3. **The app is payload-agnostic.** `Installer.loaderName()` / `isX64()` read the payload (ELF
   `e_machine`), never `Build.SUPPORTED_ABIS` — on this image that list contains `arm64-v8a`
   because of the native bridge. Installation copies whatever `assets/lib` holds and deletes a
   stale shim when the packaged payload has none. Stamp is `payload-v6/<loader>`.
4. **`ServerService`** writes the resolver file for both layouts (absolute for arm64, `<project>/.resolv.conf`
   for x64), launches `[shim] ld.so --library-path <lib> opencode-bin serve …`, and passes
   `--library-path` explicitly as well as `LD_LIBRARY_PATH`.
5. **`debuggable` removed** — a release build should not be debuggable; the cost is no `run-as`.
6. **Build fixes.** The clean step used to delete **both** APK names, so building arm64 silently
   destroyed the finished x64 artifact. It now cleans only `$APK_NAME`. `shim/build.ps1` reproduces
   the shim **byte-identically** from source, and `build.ps1 -Arch x64` calls it when the binary is
   absent.
7. **Staging is reproducible from a clone:** `stage-assets-v2.ps1` (arm64) writes `assets/arm64/`,
   the new `stage-assets-x64.ps1` writes `assets/x64/` and builds the shim. Re-running the x64 one
   against npm produced a byte-identical `opencode-bin` and shim.
8. Docs: `docs/OPENCODE-V2.md` §6–8 rewritten with the seccomp findings and the evidence;
   `README.md`, `docs/DEV-ENVIRONMENT.md` corrected (the emulator **can** run the payload now).
9. **Account sign-in (new).** Setup now drives the server's device-code OAuth flow for the
   `opencode` (OpenCode Console) integration: a "Sign in with OpenCode" button shows the code
   (e.g. `VBHT-BLJP`) and verification URL, polls until complete/failed/expired, and on success
   shows "Connected: ..." with a "Change account" button. Manual key paste remains as a fallback
   (with Paste / Show-Hide). MainActivity gained a top-right gear button that reopens Setup, so
   sign-in/sign-out is reachable from inside the running app. Verified on the emulator: begin +
   poll returns a live device code and opens the browser; the final approval step needs a real
   OpenCode account.

## 3. Restore a payload and build

Payloads, APKs and the keystore are gitignored. From a fresh clone:

```powershell
# arm64 (needs a released arm64 APK as the libc source - v1.4/v1.5/v1.6 all work)
powershell -File .\ocstandalone\stage-assets-v2.ps1  -FromApk C:\path\opencode-solo.apk

# x86_64 (needs a released x64 APK as the musl lib source; builds the shim too)
powershell -File .\ocstandalone\stage-assets-x64.ps1 -FromApk C:\path\opencode-solo-x64.apk

$env:KS_ALIAS='opencode'; $env:KS_PASS='opencode'
$env:ANDROID_HOME='G:\dev\android-sdk'; $env:GRADLE_USER_HOME='G:\dev\gradle-home'
powershell -ExecutionPolicy Bypass -File .\ocstandalone\build.ps1              # arm64
powershell -ExecutionPolicy Bypass -File .\ocstandalone\build.ps1 -Arch x64    # x86_64
```

Outputs: `ocstandalone\build\opencode-solo.apk` and `opencode-solo-x64.apk` (they coexist).
`build.ps1` uses build-tools `36.0.0` + `platforms\android-37.0`, patches the resolver string per
arch (and throws if the string it expects is gone), and validates the shim's ELF header.

## 4. Driving the emulator (the normal loop now)

```powershell
$adb = 'G:\dev\android-sdk\platform-tools\adb.exe'
G:\dev\android-sdk\emulator\emulator.exe -avd Pixel_10_API_37 -port 5554

& $adb -s emulator-5554 install -r .\ocstandalone\build\opencode-solo-x64.apk
& $adb -s emulator-5554 shell am start -n app.opencode.standalone/.MainActivity
```

First run shows the setup screen. Its on-screen counter does **not** refresh after `pm grant`, so
grant from the shell and ignore the count (or tap each row; the WebView exposes no clickable nodes
to `uiautomator`, only one full-screen node — use screenshots for bounds):

```powershell
& $adb -s emulator-5554 shell "pm grant app.opencode.standalone android.permission.POST_NOTIFICATIONS; \
  pm grant app.opencode.standalone android.permission.READ_MEDIA_IMAGES; \
  pm grant app.opencode.standalone android.permission.READ_MEDIA_VIDEO; \
  pm grant app.opencode.standalone android.permission.READ_MEDIA_AUDIO; \
  pm grant app.opencode.standalone android.permission.CAMERA; \
  pm grant app.opencode.standalone android.permission.READ_CONTACTS; \
  pm grant app.opencode.standalone android.permission.WRITE_CONTACTS; \
  pm grant app.opencode.standalone android.permission.ACCESS_FINE_LOCATION; \
  pm grant app.opencode.standalone android.permission.ACCESS_COARSE_LOCATION; \
  pm grant app.opencode.standalone android.permission.BLUETOOTH_CONNECT; \
  pm grant app.opencode.standalone android.permission.BLUETOOTH_SCAN; \
  pm grant app.opencode.standalone android.permission.READ_PHONE_STATE; \
  pm grant app.opencode.standalone android.permission.RECORD_AUDIO; \
  appops set app.opencode.standalone MANAGE_EXTERNAL_STORAGE allow"
```

Then tap **Continue** (bottom of the setup page; on a 1080×2424 screen it was `input tap 540 2140`)
and wait for the UI. Reach the API through the **auth proxy**:

```powershell
& $adb -s emulator-5554 forward tcp:8099 tcp:4097
Invoke-WebRequest http://127.0.0.1:8099/api/info -UseBasicParsing   # 200, version 2.1.4
# tcp:4096 direct is 401 by design (HTTP Basic) - the 200/401 pair proves the proxy works
```

Prompt body — **not** `{parts:[]}` and **not** `{prompt:{…}}`, both 400:

```json
{"text":"…","model":{"providerID":"opencode","id":"big-pickle"}}
```

`POST http://127.0.0.1:8099/api/session/<id>/prompt`, then poll
`GET /api/session/<id>/message`. `opencode` / `big-pickle` needs no API key and costs 0.

Logs and screenshots:

```powershell
& $adb -s emulator-5554 logcat -d -s ocstandalone:*          # server stdout/stderr as srv| …
& $adb -s emulator-5554 shell screencap -p /sdcard/x.png
& $adb -s emulator-5554 pull /sdcard/x.png "$env:TEMP\x.png"
```

The two committed proof screenshots are `docs/evidence/first-run-setup.png` (the setup screen) and
`docs/evidence/x64-emulator-shell-tool.png` (the agent's shell tool answering inside the app).

## 5. Publishing a release

```powershell
$env:GH_TOKEN = (gh auth token)
gh release create v1.6 -R mhzsajan/opencode-android --prerelease `
  --title "v1.6 - arm64 + x86_64 (emulator) payloads" --notes-file notes.md `
  .\ocstandalone\build\opencode-solo.apk .\ocstandalone\build\opencode-solo-x64.apk
```

Verify by **downloading the asset back** and comparing SHA-256 with the local file — GitHub has
served truncated assets before, and a checksum written from the local file proves nothing about
what was stored. GitHub connectivity here is flaky: retry `git push` and `gh release` once before
reporting failure. `gh` is authenticated as `mhzsajan`.

## 6. Keystore (2026-10-24)

The old key was **found, not lost** — a copy survived in the DSH workspace — so no new key had to
be generated. Every release since v1.4 uses the same certificate:

```
ocstandalone\build\launcher.keystore   (gitignored)
backup   : G:\dev\keystores\launcher.keystore    <- outside every repo; do not delete
alias    : opencode
password : opencode
DN       : CN=opencode-android, O=mhzsajan, C=NP
valid    : 2026-10-24 .. 2054-03-11
SHA-256  : 84:E7:50:34:D2:46:02:99:76:EB:2A:6B:33:CE:99:AD:E4:9D:BC:89:05:AB:6A:B3:CA:B3:52:D9:F8:10:6C:31
```

**Back this file up outside the repo.** Without it, new builds cannot update the installed copy;
Android refuses a signature change. It is deliberately not committed.

## 7. Hard-won facts (do not relearn these)

- **`targetSdk 28` is required** for `ocstandalone`: Android 10+ W^X denies `execve()` of
  `app_data_file` for apps targeting API 29+, and the bundled loader must be executed from the
  app's files dir. Termux is targetSdk 28 for the same reason.
- **The same targetSdk is why the shim exists.** `untrusted_app_27` gets a bionic-derived seccomp
  filter; `SECCOMP_RET_TRAP` on legacy syscalls kills a musl payload at startup. Do not "simplify"
  the shim away, and do not ship it with arm64.
- **Each payload's resolver path is compiled in.** `build.ps1` rewrites one string per arch and
  throws if it is missing. arm64 → absolute app path; x64 → relative `.resolv.conf` written into
  the child's cwd. Both are documented in `docs/OPENCODE-V2.md` §6.4.
- **Payload selection is asset-driven.** `Build.SUPPORTED_ABIS` reports `arm64-v8a` on the x86_64
  image (native bridge) and would pick the wrong loader.
- **A non-debuggable build has no `run-as`.** Swapping `files/shim` for an experiment needs a
  debuggable build, and `am force-stop` first — otherwise `cp` fails with `Text file busy`. A
  payload started from a shell survives `am force-stop`; kill it with `run-as … kill -9 <pid>`.
- **`aapt2 -A` on Windows writes backslash asset paths**, which `AssetManager` cannot open. Add
  assets with explicit `/` names (the build script does).
- **`aapt2` compresses assets**, so `AssetManager.openFd()` throws "probably compressed"; use
  `open()` and stream. That is why a 239 MB payload yields a 114 MB APK.
- **`npm pack` writes notices to stderr**, and `$ErrorActionPreference='Stop'` turns each one into
  a terminating error. Both staging scripts run it with `Continue` and parse the `.tgz` line.
- **`ptrace(PTRACE_PEEKDATA)` returns `-EFAULT`** under this filter; `POKEDATA` works. The shim
  pokes a `timespec` into the tracee's stack for `poll`→`ppoll`, below the red zone (`rsp-512`,
  then `-256`, `-128`).
- **models.dev times out** (`Operation timed out after '10s'`) because `api.json` is 5.9 MB and
  takes ~12 s here. It predates the shim and does not stop the built-in provider from answering.
  Link-bandwidth fact, not a bug to chase.
- **`NetworkOnMainThreadException` in any UI path** — swallow it and you get an infinite
  "not ready" loop with no log. `ping()` must always be off the main thread.
- Android refuses to show a permission dialog for a permission declared with `maxSdkVersion` below
  the device API; `requestPermissions()` just returns denied. All-files access has its own row and
  is the supported path on 10+.
- The cleartext config (`res/xml/network_security_config.xml`) is required at targetSdk 28;
  `127.0.0.1` must be whitelisted. Do not remove it.
- **No arm64 emulator on this host**; `libndk_translation` starts the arm64 payload but Bun panics
  on `futex_wait` (`docs/DEV-ENVIRONMENT.md` §5).
- **`android emulator list` does not see AVDs under `ANDROID_AVD_HOME`.** Use
  `emulator.exe -list-avds`, and launch with `emulator.exe`, not the `android` CLI.
- **A 200 from the v2 UI proves nothing about auth; the 200/401 pair does.**
- **The setup counter does not re-render after `pm grant`**; check with
  `dumpsys package app.opencode.standalone | grep granted=true`.
- **`adb install` can read the APK directly**; on-device `pm install` cannot read FUSE paths
  (`/storage/emulated/0`) because `system_server` has no access — copy to `/data/local/tmp` first.
- **Screenshots:** `screencap -p /sdcard/x.png` then `adb pull`; piping `exec-out` through
  PowerShell `>` corrupts the PNG.
- **`adb shell pm …` may throw `SecurityException: … user 150`** on the phone (second profile);
  primary user 0 works.
- Ports **4096/4097** are used on-device (server, auth proxy). A stale `adb forward` can make a
  dead server look alive. On this host ports 4096/4097 are *also* burned by dead Windows sockets —
  forward to other local ports (8099 etc.), not to the same number.

Git from this sandbox:

- HTTPS with the default schannel backend fails with
  `schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS`; use
  `git -c http.sslBackend=openssl -c credential.helper= clone https://<gh-token>@github.com/...`
  (`gh auth token`).
- Local clones can fail (`sh.exe: couldn't create signal pipe`); copy the working tree with
  `Copy-Item` instead.

## 8. Security

- An opencode API key (`oc_sk_...`) once leaked into a session log in an earlier handoff. Rotate it
  if that transcript still exists anywhere.
- Nothing large or secret is committed: no payload, no APK, no keystore, no `android.jar` (all
  gitignored). The GitHub release assets **do** contain the payloads — that is why they are the
  documented restore source for the staging scripts.
- The notification-listener permission is **not** needed by either app; only `POST_NOTIFICATIONS`
  for the foreground-service notification.

## 9. Next steps

1. **Test the WebSocket upgrade path** through `AuthProxy` — the only untested hop in the UI.
2. **Re-verify the arm64 artifact on hardware** if a phone ever becomes available; today its proof
   is v1.5 on the phone plus the shared Java verified on x64.
3. Optional polish: reopen setup from inside the app, and an in-app way to change the project
   directory.
