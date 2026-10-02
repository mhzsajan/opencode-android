# opencode Android — session handoff

Written 2026-10-02 ~10:20. Read this first after any disconnect.

---

## 1. TL;DR

Two Android apps exist. The **Termux-backed one works**. The **standalone one is 90% done** —
its server runs perfectly with no Termux, but its WebView never finishes loading, so the UI
shows a blank dark screen with a spinner. That is the one open bug.

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

## 4. Open bug — standalone UI is blank

**Symptom:** dark screen, spinner in the middle, forever. The status bar shows a download
arrow, so a load was attempted. `onPageFinished` never fires (the app's ProgressBar is still
visible, and that bar is only hidden in `onPageFinished`).

**Leading hypothesis (not yet confirmed):** the app's own `ping()` is failing, so `load()` is
never reached. In `MainActivity` the WebView starts as `View.GONE` and only becomes visible
inside `load()`. If `ping()` never succeeds you see exactly this: the root FrameLayout's dark
background plus the spinner, with the WebView never shown.

**Next steps, in order:**

1. Add a `Log.d(TAG, "ping=" + ok)` inside `ping()` and a log in `loadWhenReady()` /
   `load()`. Watch with `logcat -s ocstandalone`. This confirms or kills the hypothesis in
   one build.
2. Cheap belt-and-braces fix regardless: stop hiding the WebView — set it `VISIBLE` at
   creation, and drive the spinner with a separate view. Then a stuck load shows the real
   Chromium error page instead of a featureless dark screen.
3. If `ping()` is the problem, suspect the network security config. The app reuses
   `res/xml/network_security_config.xml` from oclaunch, which permits cleartext for
   `127.0.0.1` and `localhost` only. Try removing the config entirely — with `targetSdk 28`
   cleartext is permitted by default, so the config is not needed and may be the thing
   breaking it.
4. Note the standalone server starts with a **fresh, empty `HOME`**
   (`filesDir/home`), so it has **no `auth.json` and no API key**. Even once the UI paints,
   the chat will not work until a provider key is configured inside the app. Plan for this:
   either copy an `auth.json` into `filesDir/home/.local/share/opencode/`, or add a
   "paste your key" screen. This is a known gap, not a mystery bug.

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

## 6. GitHub push — BLOCKED, needs you

Not pushed. Blockers, all requiring you:

1. **No repository URL.** I will not guess one.
2. **No `gh` CLI** installed in Termux.
3. **No git identity** configured (`user.name` / `user.email` are empty).
4. **No credentials.** No SSH private key (only `~/.ssh/authorized_keys`), and no HTTPS
   token.

To finish it, give me the repo URL plus one of:

- a GitHub PAT (HTTPS), or
- have me generate an SSH key and you add the public key to GitHub, or
- run `git remote add origin <url>` yourself, then I can commit-ready the rest.

Everything is committed locally already, so pushing is a one-liner once credentials exist.

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
