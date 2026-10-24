# opencode Android — session handoff

Updated **2026-10-24 evening** (previous version: 2026-10-02/03). Read this first after any
disconnect.

---

## 1. TL;DR

| | State |
|---|---|
| `ocstandalone` v1.4 — `master` | **Working on-device, released.** ~62 MB, opencode 1.18.34. The long-standing upstream-network bug is fixed — the bundled glibc's compiled-in resolver path now points at an app-writable `resolv.conf`. See `docs/STANDALONE-NETWORK.md`. |
| `ocstandalone` v2 — merged into `master` at `9422ba5` | **Working on-device, verified 2026-10-24, not yet released.** 114 751 180 bytes, opencode 2.1.4, in-app loopback auth proxy on 4097. Still `versionCode 5` / `1.4`; needs the 1.5 bump and a pre-release tag. See `docs/OPENCODE-V2.md`. |
| `octermux` (Termux-backed, ~30 KB) | Builds clean; main-thread `ping()` bug fixed and Windows build added 2026-10-24. **Not yet tested on device** (Termux had just been reinstalled and has no opencode CLI). |
| `oclaunch` | Legacy, superseded by `octermux`. Unchanged. |

## 2. Device state (Samsung SM-S908E, Android 16, arm64)

- `app.opencode.standalone` **v2 (114 751 180 bytes, versionCode 5 / 1.4) is installed and
  verified working** — installed 2026-10-24 20:32 with `adb install -r` over the previous build,
  data kept, signed with the keystore in section 6. The server runs as the app UID, the v2 UI
  renders live data, and a free model answers. Details in `docs/OPENCODE-V2.md` §5.
- **The phone is back with its owner as of 2026-10-24.** Our adb forwards were removed and the app
  process was force-stopped; the app and its data are still installed, so relaunching it is one
  tap. Further development happens on the **x86_64 emulator** (`Pixel_10_API_37`,
  `emulator-5554`) — see `docs/DEV-ENVIRONMENT.md`. Payload-level testing still needs the phone:
  the payload is an aarch64 glibc ELF and **no arm64 emulator can run on this host**.
- The project/workspace directory used by the server is `/storage/emulated/0/OpenCode`
  (all-files access is granted; that row in the setup screen shows "files: yes").
- Termux 0.118.0 is installed but fresh (2026-10-24 09:24): its opencode CLI, `auth.json` and
  `~/.shortcuts/oc-server` are **not** confirmed present, so `octermux` cannot be tested until
  that is restored.
- Old debug material from the previous session is on the phone at `/sdcard/opencode/` (also
  copied into the DSH workspace at `phone-notes/opencode/`).

## 3. Build state (Windows)

Built and verified with `build-tools 36.0.0` + JDK 21 against `platforms/android-37.0`. The SDK
now lives at **`G:\dev\android-sdk`** (see `docs/DEV-ENVIRONMENT.md`; `ANDROID_HOME` previously
named a nonexistent path, which is why env-var-dependent builds failed).

| Artifact | Version | Size | SHA-256 |
|---|---|---|---|
| `ocstandalone/build/opencode-solo.apk` (v1.4, `master` before v2) | 1.4 (code 5) | 62 121 676 | `93570CE4008169A24CD985E438E5246F8198C71C45D62E4DF4E0DCF9D30C253C` |
| `ocstandalone/build/opencode-solo.apk` (**v2, verified on device**) | 1.4 (code 5) | 114 751 180 | `CC90EE85C931E97BFE165A92C39AD432F84329220F3FD988D62D7E169BBAC86A` |
| `octermux/build/opencode-termux.apk` | 1.1 (code 2) | 30 003 | `B227814DB61C2FD31E5431A1DD298E756679F48BF29FEA8AB7EF25AEE403F9AA` |

Both `ocstandalone` builds report code 5 / 1.4 — the v2 build is the same version number, so
`adb install -r` upgrades in place. The next build should be **1.5 / code 6**.

Checks: `apksigner verify` passes; `aapt2 dump badging` shows package/versions; the APK contains
`classes.dex` plus the full `assets/opencode-bin` and `assets/lib/*` payload (with forward-slash
asset entry names — see section 7).

Reproduce:

```powershell
$env:ANDROID_HOME = 'G:\dev\android-sdk'
$env:JAVA_HOME    = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:KS_ALIAS='opencode'; $env:KS_PASS='opencode'

# octermux (no payload needed)
powershell -ExecutionPolicy Bypass -File .\octermux\build.ps1

# ocstandalone (payload must be staged first)
.\ocstandalone\stage-assets.ps1 -FromApk _incoming\opencode-android-v1.0.apk
powershell -ExecutionPolicy Bypass -File .\ocstandalone\build.ps1
```

Install: `adb install -r <apk>`. Reinstalling over the working app keeps its data.

## 4. What changed on 2026-10-24

1. **Standalone networking fixed (the big one).** The bundled libc had Termux's resolver path
   compiled in (`/data/data/com.termux/files/usr/glibc/etc/resolv.conf`), which does not exist
   for the app UID, so glibc never had a nameserver. `build.ps1` now patches that one string to
   `/data/data/app.opencode.standalone/files/resolv.conf`; `ServerService.writeResolvConf()`
   writes the phone's live DNS servers (ConnectivityManager) plus 8.8.8.8/1.1.1.1 there before
   spawning; `Installer.STAMP` is `payload-v2` so existing installs re-extract. The dead
   `DNS_RESULT_ORDER`/`DNS_AUTO_FALLBACK` env vars were removed. Verified: no more
   "Failed to fetch models.dev", 6.5 MB live provider catalog, and a real chat request returned
   `pong`. Full write-up: `docs/STANDALONE-NETWORK.md`.
2. **Windows packaging bug fixed.** `aapt2 -A` on Windows wrote asset entry names with
   backslashes (`assets/lib\libc.so.6`), so `AssetManager.open("lib/...")` threw
   `FileNotFoundException` on first launch. The build script now adds assets and `classes.dex`
   itself with correct `/` separators, then zipaligns and signs.
3. **Setup screen: dead "Files & folders" row.** READ/WRITE_EXTERNAL_STORAGE are capped at
   `maxSdkVersion=28`, so on Android 10+ the Allow button silently did nothing. The row is now
   only shown on API ≤ 28; "All files access" is the supported path on newer Android (it was
   already granted and reported as "files: yes").
4. **`octermux` main-thread `ping()` fixed** (`HttpURLConnection` on the UI thread threw
   `NetworkOnMainThreadException`, was swallowed, and the app waited forever — same bug class as
   the old standalone blank UI). Now async with a 180 s budget and status text.
5. **`octermux/build.ps1` added**, version bumped to 1.1 / code 2.
6. Docs: `docs/STANDALONE-NETWORK.md` written (it was referenced by the code but missing),
   README and this handoff rewritten.
7. **opencode v2, same night.** Merged `v2` into `master` (`9422ba5`) and put it on the phone.
   v2's API sits behind HTTP Basic auth that a WebView will not answer for its own `fetch`/XHR/
   `EventSource` calls, so `AuthProxy` injects the credential on a loopback hop (WebView → 4097 →
   server on 4096). Verified end to end on the device: no 401s, UI renders live data, a free
   model answers. Full write-up: `docs/OPENCODE-V2.md`.
8. **Development moved to the x86_64 emulator.** No arm64 emulator can run on this host (§7), and
   Android Studio stays on `C:` while the SDK/AVDs/Gradle live on `G:`. Layout, env vars and the
   verified toolchain: `docs/DEV-ENVIRONMENT.md`.

## 5. Next steps

1. **Ship v2 as 1.5.** Bump to `versionCode 6` / `versionName 1.5`, rebuild, tag a **pre-release**
   with the APK, and say in the notes that v1 stays at 1.4 as the fallback. The verified build is
   still code 5, so shipping as-is makes the release indistinguishable from the v1.4 install it
   replaces.
2. **Make the emulator a full dev target** — the blocker for working without the phone. Stage the
   x86_64 payload (`@opencode/cli-linux-x64@2.1.4`, 240 417 179 bytes unpacked) with x86_64 glibc,
   re-run the resolver-patch guard against the new `libc.so.6`, and select the loader from
   `Build.SUPPORTED_ABIS` in `Installer.java`, which hardcodes `ld-linux-aarch64.so.1` in two
   places. Whether a glibc x86_64 ELF executes under the emulator is unproven.
3. **Restore Termux for `octermux`** (only if that path is wanted): install the opencode CLI in
   Termux, write `~/.shortcuts/oc-server`, set `allow-external-apps=true`, install
   `octermux/build/opencode-termux.apk`, grant the RUN_COMMAND permission. The app code is fixed
   but has never been run on this freshly reinstalled Termux.
4. **Provider key (optional).** Free models on the `opencode` provider work with no key (that is
   how the on-device chat test passed). A paid provider needs a key: either paste it into the
   app's setup screen (writes `filesDir/home/.local/share/opencode/auth.json`) or add it from
   the opencode UI.
5. Possible polish: reopen the setup screen from inside the app (currently only shown on first
   run), and an in-app way to change the project directory.

## 6. Keystore (2026-10-24)

The old key was **found, not lost** — a copy survived in the DSH workspace — so no new key had to
be generated. The v1.4 release and the v2 build are signed with the same certificate:

```
ocstandalone\build\launcher.keystore   (gitignored)
backup   : G:\dev\keystores\launcher.keystore    <- outside every repo; do not delete
alias    : opencode
password : opencode
DN       : CN=opencode-android, O=mhzsajan, C=NP
valid    : 2026-10-24 .. 2054-03-11
SHA-256  : 84:E7:50:34:D2:46:02:99:76:EB:2A:6B:33:CE:99:AD:E4:9D:BC:89:05:AB:6A:B3:CA:B3:52:D9:F8:10:6C:31
```

**Back this file up outside the repo.** Without it, upcoming builds cannot update the installed
copy; Android refuses a signature change. It is deliberately not committed (it is a signing key,
and `build/` is gitignored) — the `G:` copy above is the backup.

## 7. Hard-won environment facts (do not relearn these)

- **`targetSdk 28` is required** for `ocstandalone`: Android 10+ W^X denies `execve()` of
  `app_data_file` for apps targeting API 29+, and the bundled glibc loader must be executed from
  the app's files dir. Termux is targetSdk 28 for the same reason.
- **The bundled glibc resolver path is compiled in.** Any payload swap needs the resolver patch
  re-checked (`build.ps1` throws if the original string is missing).
- **`aapt2 -A` on Windows writes backslash asset paths** which `AssetManager` cannot open. Add
  assets with explicit `/` names instead (the build script does).
- **`NetworkOnMainThreadException` in any UI path** — swallow it and you get an infinite
  "not ready" loop with no log. This bit both apps; `ping()` must always be off the main thread.
- Android refuses to show a permission dialog for a permission declared with `maxSdkVersion`
  below the device API; `requestPermissions()` just returns denied. Don't put such rows in UI.
- The cleartext config (`res/xml/network_security_config.xml`) is required: at targetSdk 28
  cleartext defaults to denied and `127.0.0.1` must be whitelisted. Do not remove it.
- `aapt2` compresses assets, so `AssetManager.openFd()` throws "probably compressed"; use
  `open()` and stream. This is why the 181 MB payload yields a ~62 MB APK.
- `adb install` can read the APK directly; on-device installs (`pm install`) cannot read FUSE
  paths (`/storage/emulated/0`) because `system_server` has no access — copy to
  `/data/local/tmp` first.
- Screenshots over adb: `adb shell screencap -p /sdcard/x.png` then `adb pull` (piping
  `adb exec-out screencap` through PowerShell `>` redirection corrupts the PNG).
- `adb shell pm …` may throw `SecurityException: Shell does not have permission to access user
  150` on this phone (second user profile exists); primary user 0 works.
- Debugging the embedded server: `adb logcat -s ocstandalone:*` mirrors its stdout/stderr as
  `srv| ...`. The phone-side log file is unreadable over adb.
- `adb forward tcp:4096 tcp:4096` exposes the on-device server API to the PC for quick checks
  (`/provider`, `/session`, `POST /session/<id>/message`).

- **No arm64 emulator can run on this host.** The arm64 system image installs and `avdmanager`
  creates the AVD, but the emulator exits in under two seconds:
  `FATAL | Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on x86_64 host`.
  There is no cross-architecture TCG fallback on Windows, and `libndk_translation` only translates
  arm64 native *libraries* inside an app process, not `execve()` of a glibc ELF. Payload-level
  testing therefore needs the physical phone (`docs/DEV-ENVIRONMENT.md` §5).
- **`android emulator list` does not see AVDs created under `ANDROID_AVD_HOME`** — the new CLI
  keeps a separate registry. Use `emulator.exe -list-avds` / `avdmanager list avd`, and launch
  with `emulator.exe`, not the `android` CLI.
- **A 200 from the v2 UI proves nothing about auth; the 200/401 pair does.** `adb forward
  tcp:4097` → `/api/info` 200 while `tcp:4096` → 401 is what proves the loopback proxy is
  supplying credentials, rather than a cached cookie making the UI look healthy.
- **Two copies of this repo exist on this machine** (this one and the DSH workspace copy that held
  the signed APK and the 236 MB staged payload). Do not run two renders/builds against one tree,
  and check which one you are in before building.
- Android Studio stays on `C:`; the SDK, AVDs and Gradle cache live on `G:` via `ANDROID_HOME`,
  `ANDROID_AVD_HOME` and `GRADLE_USER_HOME`. Layout, sizes and the verified toolchain:
  `docs/DEV-ENVIRONMENT.md`.

Git from this sandbox:

- HTTPS with the default schannel backend fails with
  `schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS`; use
  `git -c http.sslBackend=openssl -c credential.helper= clone https://<gh-token>@github.com/...`
  (`gh auth token`).
- Local clones can fail (`sh.exe: couldn't create signal pipe`); copy the working tree with
  `Copy-Item` instead of `git clone` when moving it between folders.

## 8. Security

- An opencode API key (`oc_sk_...`) once leaked into a session log in the previous handoff.
  Rotate it if that transcript still exists anywhere.
- Nothing large or secret is committed: no payload, no APK, no keystore, no `android.jar`
  (all gitignored). The GitHub release asset does contain the payload — that is why it is a
  convenient restore source for `stage-assets.ps1`.
- The notification-listener permission is **not** needed by either app; only
  `POST_NOTIFICATIONS` for the foreground-service notification.
