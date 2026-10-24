# opencode Android — session handoff

Updated **2026-10-24 evening** (previous version: 2026-10-02/03). Read this first after any
disconnect.

---

## 1. TL;DR

| | State |
|---|---|
| `ocstandalone` (self-contained, ~62 MB) | **Working on-device. Verified 2026-10-24: UI loads, server runs, chat answers.** The long-standing upstream-network bug is fixed — the bundled glibc's compiled-in resolver path now points at an app-writable `resolv.conf`. See `docs/STANDALONE-NETWORK.md`. |
| `octermux` (Termux-backed, ~30 KB) | Builds clean; main-thread `ping()` bug fixed and Windows build added 2026-10-24. **Not yet tested on device** (Termux had just been reinstalled and has no opencode CLI). |
| `oclaunch` | Legacy, superseded by `octermux`. Unchanged. |

## 2. Device state (Samsung SM-S908E, Android 16, arm64)

- `app.opencode.standalone` **v1.4 (versionCode 5) is installed and working** (built 2026-10-24,
  signed with the new keystore, see section 6). Payload was extracted to app-private storage;
  the server runs as the app UID; chat sessions work (free models, no key needed).
- The project/workspace directory used by the server is `/storage/emulated/0/OpenCode`
  (all-files access is granted; that row in the setup screen shows "files: yes").
- Termux 0.118.0 is installed but fresh (2026-10-24 09:24): its opencode CLI, `auth.json` and
  `~/.shortcuts/oc-server` are **not** confirmed present, so `octermux` cannot be tested until
  that is restored.
- Old debug material from the previous session is on the phone at `/sdcard/opencode/` (also
  copied into the DSH workspace at `phone-notes/opencode/`).

## 3. Build state (Windows)

Built and verified with `build-tools 36.0.0` + JDK 21 against `platforms/android-37.0`:

| Artifact | Version | Size | SHA-256 |
|---|---|---|---|
| `ocstandalone/build/opencode-solo.apk` | 1.4 (code 5) | 62 121 676 | `93570CE4008169A24CD985E438E5246F8198C71C45D62E4DF4E0DCF9D30C253C` |
| `octermux/build/opencode-termux.apk` | 1.1 (code 2) | 30 003 | `B227814DB61C2FD31E5431A1DD298E756679F48BF29FEA8AB7EF25AEE403F9AA` |

Checks: `apksigner verify` passes; `aapt2 dump badging` shows package/versions; the APK contains
`classes.dex` plus the full `assets/opencode-bin` and `assets/lib/*` payload (with forward-slash
asset entry names — see section 7).

Reproduce:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
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

## 5. Next steps

1. **Restore Termux for `octermux`** (only if that path is wanted): install the opencode CLI in
   Termux, write `~/.shortcuts/oc-server`, set `allow-external-apps=true`, install
   `octermux/build/opencode-termux.apk`, grant the RUN_COMMAND permission. The app code is fixed
   but has never been run on this freshly reinstalled Termux.
2. **Provider key (optional).** Free models on the `opencode` provider work with no key (that is
   how the on-device chat test passed). A paid provider needs a key: either paste it into the
   app's setup screen (writes `filesDir/home/.local/share/opencode/auth.json`) or add it from
   the opencode UI.
3. **Release / push.** The local repo is ahead of `origin`. A new GitHub release needs the APKs;
   note the v1.0 release was signed with the old, lost keystore — users must uninstall that
   build before installing a new one.
4. Possible polish: reopen the setup screen from inside the app (currently only shown on first
   run), and an in-app way to change the project directory.

## 6. Keystore (new, 2026-10-24)

The old backup (`C:\Users\o0o\Documents\opencode-backup\...`) no longer exists, so a new keystore
was generated:

```
ocstandalone\build\launcher.keystore   (gitignored)
alias    : opencode
password : opencode
DN       : CN=opencode-android, O=mhzsajan, C=NP
```

**Back this file up outside the repo.** Without it, upcoming builds cannot update the installed
copy; Android refuses a signature change.

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
