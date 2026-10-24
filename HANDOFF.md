# opencode Android — session handoff

Updated **2026-10-24** (previous version: 2026-10-02/03). Read this first after any disconnect.

---

## 1. TL;DR

| | State |
|---|---|
| `octermux` (Termux-backed, ~30 KB) | **Builds clean. Ping bug fixed 2026-10-24** (it had the same main-thread `HttpURLConnection` bug that made `ocstandalone` look blank). Device test pending. |
| `ocstandalone` (self-contained, ~62 MB) | **Builds clean** on Windows (v1.4). Upstream networking in the app UID is still unresolved — root cause narrowed down statically, see `docs/STANDALONE-NETWORK.md`. |
| `oclaunch` | Legacy, superseded by `octermux`. Unchanged. |

The phone was **in use for another project** during the 2026-10-24 session, so the build work was
done offline (no installs, launches or taps after the user asked to stop). Everything below is
either verified by static APK checks or explicitly marked as device-pending.

## 2. Device state (Samsung SM-S908E, Android 16, arm64)

- **Termux 0.118.0 installed fresh on 2026-10-24 09:24.** Its opencode setup (CLI in
  `~/.opencode/bin`, `auth.json`, `myproject`) is *not* confirmed present after the reinstall.
  `octermux` cannot work until that is restored (see section 5).
- `app.opencode.standalone` v1.4 was installed on 2026-10-24 ~18:11 during this session, signed
  with a **new** keystore (see section 6). First-run setup was on screen when device work was
  paused; the app was not exercised end to end.
- `app.opencode.launcher` and `app.opencode.termux` are **not** installed.
- Old notes/handoff material lives on the phone in `/sdcard/opencode/` (also copied into the
  DSH workspace at `phone-notes/opencode/`).

## 3. Build state (Windows, offline)

Built and verified with `build-tools 36.0.0` + JDK 21 against `platforms/android-37.0`:

| Artifact | Version | Size | SHA-256 |
|---|---|---|---|
| `ocstandalone/build/opencode-solo.apk` | 1.4 (versionCode 5) | 62 125 777 | `0F6F6EFEF62EF025D6B83B72686BFA2957C5A754B686D8402F1D3A0E39DA84FB` |
| `octermux/build/opencode-termux.apk` | 1.1 (versionCode 2) | 30 003 | (see `Get-FileHash` after a rebuild; it changes with the timestamp) |

Checks done offline: `apksigner verify` passes, `aapt2 dump badging` shows the intended
package/versionSdk, APK zip contains `classes.dex` and (for standalone) the full
`assets/opencode-bin` + `assets/lib/*` payload. `octermux`'s dex was checked to contain the new
`loadWhenReady`/`octermux-ping` symbols.

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

## 4. What changed on 2026-10-24

1. **`octermux` ping bug fixed.** `start()`/`onResume()`/`retry()` called `ping()`
   (`HttpURLConnection`) on the main thread. It throws `NetworkOnMainThreadException`, the
   exception was swallowed, and the app concluded "server not ready" forever — the exact same
   class of bug as the old `ocstandalone` blank UI. `ping()` now runs on a background thread
   (`octermux-ping`), a `waiting` flag prevents duplicate retry chains, the retry budget is 180
   attempts (3 min — cold Termux starts are slow), and the status line shows the attempt number.
2. **`octermux/build.ps1` added** — Windows build of the small app; no gradle, same
   aapt2/javac/d8/apksigner flow as `ocstandalone`. Version bumped to 1.1 / code 2.
3. **`docs/STANDALONE-NETWORK.md` written** (it was referenced by the code but did not exist).
   It records the device measurements from 2026-10-05/06, the new static analysis of the payload,
   and the candidate fix.
4. **README rewritten** for three apps, Windows builds, keystore rules, Termux setup for
   `octermux`.

Static-analysis findings worth keeping (details in `docs/STANDALONE-NETWORK.md`):

- the bundled `libc.so.6` has Termux's resolver path compiled in
  (`/data/data/com.termux/files/usr/glibc/etc/resolv.conf`, offset `0x16D4B0`); it does not exist
  for the standalone package;
- the `opencode-bin` binary contains **no** `RESOLV_CONF`, `DNS_RESULT_ORDER` or
  `DNS_AUTO_FALLBACK` strings — the `NET_ENV` entries in both apps are **no-ops**;
- consequently v1.3's "resolver override" theory and v1.4's "IPv6-first" theory cannot be right;
  the child simply has no usable resolver configuration. Test the candidate fix before trusting
  it.

## 5. Next steps, in order

1. **Standalone DNS experiment** — `docs/STANDALONE-NETWORK.md` section 4. Needs a provider key
   only for the final chat check; steps 2-3 (logcat error line, app-UID resolution probe) do not.
2. **Restore Termux for `octermux`** (if that is the path to ship): install the opencode CLI in
   Termux, write `~/.shortcuts/oc-server`, set `allow-external-apps=true`, then install and open
   the app and grant RUN_COMMAND.
3. **Provider key.** Neither app has one now. `ocstandalone` v1.3+ has a setup screen that writes
   `filesDir/home/.local/share/opencode/auth.json`; `octermux` uses Termux's own
   `~/.local/share/opencode/auth.json`. Chat stays broken without it regardless of networking.
4. **Version bump + release** once a device test passes. v1.0 release on GitHub is signed with
   the *old* lost keystore, so a new release will not install over it — uninstall first.

## 6. Keystore (new, 2026-10-24)

The old backup (`C:\Users\o0o\Documents\opencode-backup\...`) no longer exists on this PC, so a
new keystore was generated:

```
ocstandalone\build\launcher.keystore   (gitignored)
alias    : opencode
password : opencode
DN       : CN=opencode-android, O=mhzsajan, C=NP
```

**Back this file up outside the repo.** Without it, upcoming builds cannot update an installed
copy; Android refuses a signature change. Losing it means uninstall/reinstall.

## 7. Hard-won environment facts (do not relearn these)

- **`targetSdk 28` is required** for `ocstandalone`: Android 10+ W^X denies `execve()` of
  `app_data_file` for apps targeting API 29+, and the bundled glibc loader must be executed from
  the app's files dir. Termux is targetSdk 28 for the same reason.
- `aapt2` compresses assets, so `AssetManager.openFd()` throws "probably compressed"; use
  `open()` and stream. This is why the 181 MB payload yields a ~62 MB APK.
- `ocstandalone`'s manifest has no `<uses-sdk>`, so `aapt2 link` must pass
  `--min-sdk-version`/`--target-sdk-version` explicitly or the install fails with
  `INSTALL_FAILED_DEPRECATED_SDK_VERSION`.
- The cleartext config (`res/xml/network_security_config.xml`) is required: at targetSdk 28
  cleartext defaults to denied and `127.0.0.1` must be whitelisted. Do not remove it.
- `NetworkOnMainThreadException` on any `HttpURLConnection` in a UI path — swallow it in a
  `catch (Throwable)` and you get an infinite "not ready" loop with no log. This bit both apps.
- `adb install` can read the APK directly; on-device installs (`pm install`) cannot read FUSE
  paths (`/storage/emulated/0`) because `system_server` has no access — copy to
  `/data/local/tmp` first.
- Screenshots over adb: `adb shell screencap -p /sdcard/x.png` then `adb pull` (piping
  `adb exec-out screencap` through PowerShell > redirection corrupts the PNG).
- `adb shell pm list packages` and other `pm` calls can throw
  `SecurityException: Shell does not have permission to access user 150` on this phone (a second
  user profile exists); it is a warning, primary user 0 works.
- Git over HTTPS from this sandbox fails with
  `schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS`. Workaround used:
  `git -c http.sslBackend=openssl -c credential.helper= clone https://<gh-token>@github.com/...`
  (`gh auth token`). Local clones via helper processes also fail
  (`sh.exe: couldn't create signal pipe`), so copy the working tree with `Copy-Item` instead of
  `git clone` when moving it between folders.

## 8. Security

- An opencode API key (`oc_sk_...`) once leaked into a session log in the previous handoff.
  Rotate it if that transcript still exists anywhere.
- Nothing large or secret is committed: no payload, no APK, no keystore, no `android.jar`
  (all gitignored). The release asset on GitHub does contain the payload — that is why it is a
  convenient restore source for `stage-assets.ps1`.
- The notification-listener permission is **not** needed by either app; only
  `POST_NOTIFICATIONS` for the foreground-service notification.
