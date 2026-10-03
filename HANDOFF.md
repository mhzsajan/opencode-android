# opencode Android — session handoff

Written 2026-10-02 ~10:20. Read this first after any disconnect.

---

## 1. TL;DR

Two Android apps exist and **both work now**. The Termux-backed one (oclaunch) always worked.
The standalone one's blank-UI bug was **fixed on 2026-10-03** — root cause: `ping()` ran on
the main thread and always threw `NetworkOnMainThreadException`, so `load()` was never
reached; see section 4. Remaining known gap: the standalone app needs a provider API key
configured inside it before chat works (bottom of section 4).

**Device state (2026-10-03):** only `app.opencode.standalone` is installed. `app.opencode.launcher`
and the official `cc.agentlabs.opencode` app were uninstalled at the user's request (too many
identical icons caused confusion) — both remain in this repo and `build.sh` can rebuild them.

**Release:** v1.0 published at https://github.com/mhzsajan/opencode-android/releases/tag/v1.0
with `opencode-android-v1.0.apk` attached (see section 6).

---

## 2. What is installed on this phone

| Package | Version | State |
|---|---|---|
| `app.opencode.launcher` (oclaunch) | 3.2 | **Working.** Termux-backed, ~26 KB APK |
| `app.opencode.standalone` (ocstandalone) | 1.0 | Server works, **UI blank** (open bug) |
| `cc.agentlabs.opencode` | — | Pre-existing, unrelated, never touched |

Both use the same launcher icon, so pick carefully on the home screen. `oclaunch` shows as
"opencode", `ocstandalone` as "opencode solo".

---

## 3. Verified working

- **The standalone server genuinely needs no Termux.** Proof from `ps`:
  ```
  u0_a852  3383  2454  ld-linux-aarch64.so.1 /data/user/0/app.opencode.standalone/files/opencode-bin \
                 serve --port 4096 --hostname 127.0.0.1 --pure --print-logs --log-level INFO
  ```
  It runs as the app's own UID (`u0_a852`), not Termux's (`u0_a845`). Termux was force-stopped
  and had zero `oc-server` sessions at the time of this test.
- Server answers `http://127.0.0.1:4096/` with **200 in 41 ms**, serving the real UI
  (`<title>OpenCode</title>`, `id="root"`).
- JS bundle `/assets/index-jLGkMci9.js` → 200, 2 740 171 bytes, 54 ms.
- CSS `/assets/index-DLiUNAg_.css` → 200, 482 489 bytes.
- Foreground service is healthy: `isForeground=true foregroundId=41 types=0x40000000`
  (`0x40000000` = `SPECIAL_USE`), with an ongoing notification on channel `opencode_server`.
- First-run extraction of the 181 MB payload completes and the binary is executable.

---

## 4. Standalone UI blank bug — FIXED 2026-10-03

**Original symptom:** dark screen, spinner forever; `onPageFinished` never fired.

**Root cause (confirmed by instrumentation):** `ping()` used `HttpURLConnection` **on the
main thread**, so every attempt threw `android.os.NetworkOnMainThreadException`. The old
`catch (Throwable) { return false; }` swallowed it silently, so `loadWhenReady()` retried
forever and `load()` was never reached — the WebView (started `GONE`, only shown in
`load()`) never appeared. Exactly the observed symptom.

**The previous session's hypothesis was wrong on two counts:**
- It was not "unconfirmed" — it was ping failing, but for a non-network reason.
- The network security config is **fine**. No `main frame error` was ever logged after
  the fix, and cleartext was never exercised before it. Do **not** remove
  `res/xml/network_security_config.xml`: at `targetSdk 28` cleartext defaults to *denied*,
  and that config is what whitelists `127.0.0.1`/`localhost`. (The old advice "targetSdk 28
  permits cleartext by default" was backwards.)

**Fix in `MainActivity.java` (all committed):**
- `ping()` runs on a background thread (`oc-ping`); results marshalled via `runOnUiThread`.
- `waiting` flag prevents duplicate retry chains (old code ran two: beginServer + onResume,
  which doubled the retry counter).
- `loadedOnce` tracks whether the real page rendered, so `onResume` no longer pings
  synchronously; it resets `retries`, then `ensureServer()` + `loadWhenReady()`.
- WebView now starts `VISIBLE` (was `View.GONE`), so a future failure surfaces a real
  Chromium error page instead of a featureless dark screen.
- Diagnostic `Log.d(TAG, ...)` lines in ping/loadWhenReady/load/onPageFinished/
  onReceivedError/onReceivedHttpError. Tag: `ocstandalone`
  (`adb logcat -s ocstandalone:*`).

**Verified 2026-10-03:** cold start → `ping ok code=200` ~2 s after spawn →
`load http://127.0.0.1:4096/` → `page finished` → full UI renders (screenshot confirmed).

**Known gap (not a bug):** the standalone server starts with a **fresh, empty `HOME`**
(`filesDir/home`), so it has **no `auth.json` and no API key**, and the UI shows
"Nothing here yet". Chat will not work until a provider key is configured inside the app:
either copy an `auth.json` into `filesDir/home/.local/share/opencode/`, or add a
"paste your key" screen.

---

## 5. Repo

`~/build` is a git repo. One commit: `7d3c3b2`.

```
~/build/
  .gitignore          excludes payload, keystore, APKs, android.jar
  README.md           architecture, build, install, debugging
  stage-assets.sh     re-fetches the 177 MB payload + glibc (not committable)
  oclaunch/           Termux-backed app + build.sh
  ocstandalone/       standalone app + build.sh
```

**Nothing large or secret is committed.** Verified: largest tracked file is 14 KB, no
keystore, no APK, no `opencode-bin`, no `android.jar`. The keystore password was removed from
both `build.sh` files and is now required via `$KS_ALIAS` / `$KS_PASS`.

### Rebuilding

```sh
cd ~/build/ocstandalone
KS_ALIAS=opencode KS_PASS=opencode ./build.sh     # ~2 min, produces a 60 MB APK
```

`build.sh` prints instructions if `android.jar` (`~/build/sdk/android.jar`, 27 MB) or the
payload (`assets/`) is missing. `stage-assets.sh` restores the payload.

### Install (Shizuku must be running)

```sh
# rish takes commands on STDIN ONLY -- argv form silently does nothing
cat <<'EOF' | ~/bin/rish
cp /storage/emulated/0/Download/opencode-solo.apk /data/local/tmp/solo.apk
chmod 644 /data/local/tmp/solo.apk
pm install -r /data/local/tmp/solo.apk
rm -f /data/local/tmp/solo.apk
EOF
```

`pm install` cannot read FUSE paths — `system_server` has no access to
`/storage/emulated/0`, hence the copy to `/data/local/tmp` first.

---

## 6. GitHub push — DONE (2026-10-03)

Repo: **https://github.com/mhzsajan/opencode-android** (private, account `mhzsajan`).

- Pushed from the Windows PC, where `gh` CLI was already authenticated. Termux has no
  GitHub credentials and does not need them.
- **Sync topology: phone = dev working copy; PC clone = GitHub relay.**
  PC clone lives at `C:\Users\o0o\Documents\opencode-android` with two remotes:
  `origin` (GitHub) and `phone` (the Termux repo over the adb/ssh bridge).
  After committing on the phone, sync with:
  ```sh
  # on the PC:
  git fetch phone && git merge --ff-only phone/master && git push origin master
  ```
- The phone repo itself has no remotes; pushing from Termux would need a PAT, which is
  why the relay exists.

**Release v1.0 (2026-10-03):** tag `v1.0`, asset `opencode-android-v1.0.apk` (arm64, ~60 MB,
SHA-256 `66e94cff1b24b426c56dabb1a80356b707f9f2592cea546f99f5f5376ec5a6eb`). Published from
the PC with:
```sh
gh release create v1.0 <path-to-apk> --repo mhzsajan/opencode-android --notes-file <notes.md>
```
For the next release: bump versionCode/versionName in `build.sh`, rebuild, then repeat with
`v1.1`.

---

## 7. Hard-won environment facts (do not relearn these)

- **`rish` on current Shizuku only executes commands piped through stdin.**
  `rish "echo hi"` exits 0, prints nothing, and is indistinguishable from Shizuku being
  stopped. Use `cmd | rish`.
- **`pm install` cannot read FUSE paths.** `system_server` cannot read
  `/storage/emulated/0`; shell can. Copy via shell to `/data/local/tmp` first.
- **`aapt2` compresses assets**, so `AssetManager.openFd()` throws
  `FileNotFoundException: ... probably compressed`. Use `am.open()` and count bytes.
  This is also why the APK is 60 MB rather than 181 MB.
- **Android 10+ W^X blocks `execve()` of `app_data_file` for apps targeting API 29+.**
  Symptom: `java.io.IOException: error=13, Permission denied` from `UNIXProcess.forkAndExec`.
  Fix: `targetSdk 28`, which is exactly why Termux is `targetSdk 28`. The `/data` mount is
  not `noexec`, so it is SELinux policy, not the filesystem.
- **`set -o pipefail` + `| head -1`** makes a script die with SIGPIPE (exit 141). Capture into
  a variable instead.
- **The manifest has no `<uses-sdk>` element**, so `aapt2 link` must be passed
  `--min-sdk-version` / `--target-sdk-version` explicitly, or the install fails with
  `INSTALL_FAILED_DEPRECATED_SDK_VERSION ... found 0`.
- **`pkill -f` matched its own command line** and killed my shell. Use `pgrep -x <name>`
  (matches process name, cannot self-match) or explicit PIDs.
- Screenshots for debugging: `screencap -p /storage/emulated/0/Download/x.png` via rish, then
  read from `~/storage/shared/Download/x.png`. Screen is **1080x2316**; the reader downscales
  to 945 wide, so multiply observed coordinates by ~1.143 to get real tap coordinates.
- `df` and `ps` in Termux only see Termux's own processes. Use rish for device-wide views.

---

## 8. Security

- The OpenCode API key was printed into a session log while reading
  `~/.local/share/opencode/auth.json` (`oc_sk_caa4664e…`). **Rotate it** if that transcript
  is shared or stored anywhere.
- The keystore password is `opencode`, alias `opencode`. Both keystores are gitignored; the
  password was stripped from `build.sh`.
- Notification *access* (`BIND_NOTIFICATION_LISTENER_SERVICE`) is **not** needed and should
  not be granted to either app. Only Google Assistant, Smart Mirroring, and the Samsung
  launcher have it. The apps use `POST_NOTIFICATIONS` (auto-prompted at `targetSdk 28`) for
  their own foreground-service notification.

---

## 9. Chat "not working" in oclaunch — root cause, unrelated to the app

The `+` new-tab button was never broken; it was verified working by tapping it. The stall is
upstream:

- `big-pickle` ran 102 s and ended `MessageAbortedError` with **0 tokens and no parts**.
- Recurring `AI_APICallError: Upstream request failed: Insufficient account funds` on
  `gpt-5.4-nano` (the model opencode uses to generate session titles — which is why every
  session is literally named "New session").
- The free `mimo-v2.6-flash-free` fallback does respond.

So: an account/billing problem, not an app problem.

---

## 10. Other open items

- Sessions in oclaunch are rooted in `~/.opencode` ("No Git"), not `~/myproject`, even though
  the server's cwd is `~/myproject`. The web UI defaults the session directory to the global
  project. Not yet addressed.
- The standalone app has **no bash and no git** (Termux's are bionic binaries and cannot run
  under the glibc loader). opencode's native file tools do work. If bash/git are wanted later,
  drop glibc-built binaries into `assets/` — no architecture change needed.
- Mobile UI work landed in v3.2 of oclaunch: `WebChromeClient` (file picker, mic, console
  logging, popups), back dismisses keyboard before navigating, double-tap to exit, external
  links open in the real browser, screen stays awake. All verified on-device.
