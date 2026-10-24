# opencode v2 on Android — work in progress

Updated 2026-10-24. **Branch `v2`.** `master` still ships the verified v1 engine
(opencode 1.18.34 in v1.4); this document tracks the upgrade to opencode v2.

**Status: the auth blocker is fixed in code and the APK is built, but it has not been tested on
a device yet** (the phone was in use). No release should be cut until the on-device test in
section 5 passes.

## 1. What v2 is and where it lives

- The v2 line is published as the npm package **`@opencode/cli`** (latest **2.1.4**,
  2026-10-23). The old `opencode-ai` line continues separately at **1.19.4** — that is why the
  GitHub release page and the old package still look "v1 only".
- The GitHub repo's default branch is `dev` (`package.json` version `2.1.4`, tags `v2.0.32` …
  `v2.1.4`) and there is a `2.0` branch, but **no GitHub release** for v2 yet: the binaries are
  distributed through npm.
- The platform package **`@opencode/cli-linux-arm64@2.1.4`** contains a single
  `package/bin/opencode`: a Bun-compiled glibc aarch64 ELF, **236 112 888 bytes**, interpreter
  `/lib/ld-linux-aarch64.so.1`, needs up to `GLIBC_2.30` symbols. It runs on the phone with the
  bundled Termux glibc loader (which provides up to `GLIBC_2.44`).

## 2. Verified on the phone (SM-S908E, Android 16)

- `ld-linux-aarch64.so.1 ./opencode --version` → `opencode v2.1.4`.
- `serve` starts: database schema bootstrap + migrations, then
  `server listening on http://127.0.0.1:<port>`.
- It serves the **new v2 web UI** at `/` (HTML 200, `/_assets/*` bundles) and a **v2 JSON API**
  under `/api/*` (not the v1 `/session` shape).
- CLI differences from v1 that the app must respect:
  - `serve --port <n> --hostname 127.0.0.1 --print-logs --log-level info`;
  - **`--log-level` is lowercase** (`info`, not `INFO`) — v2 exits with `InvalidValue` otherwise;
  - **`--pure` no longer exists**;
  - `serve` also accepts `--cors`, `--service`, `--stdio`.
- **The v2 API is behind HTTP Basic auth.** Static UI files are unauthenticated, `/api/*`
  answers `401` with `www-authenticate: Basic realm="Secure Area"`. Credentials are
  **user `opencode`**, password = the server password. Set `OPENCODE_SERVER_PASSWORD` before
  spawning to make it deterministic; otherwise v2 prints a random
  `server password v5-...` at startup. (Verified with curl: `opencode:<password>` → 200.)
- **Chromium in a WebView does not answer that challenge for the UI's own requests.** With the
  WebView pointed straight at 4096, the server logged `/api/event`, `/api/info`,
  `/api/session/...` → 401, and neither `WebViewClient.onReceivedHttpAuthRequest` nor
  `WebView.setHttpAuthUsernamePassword` applied to `fetch`/`XHR`/`EventSource`. That was the
  release blocker.

## 3. The fix: in-app loopback auth proxy (`AuthProxy.java`)

`ServerService` now starts a tiny HTTP proxy on **127.0.0.1:4097** and the WebView loads
`http://127.0.0.1:4097/` instead of 4096. For each connection the proxy:

1. reads the request head, strips the client's `Connection`/`Keep-Alive`/`Authorization`,
2. adds `Authorization: Basic base64("opencode:" + password)` and `Connection: close`,
3. opens a socket to the real server on 4096 and pumps both directions until the server closes.

`Connection: close` means every browser connection carries exactly one authenticated request
(no credential ambiguity on keep-alive reuse), while responses — including the SSE stream on
`/api/event` and WebSocket upgrades — are still streamed byte-for-byte. Static assets and the
HTML therefore work unchanged, and the v2 UI's normal `fetch`/`EventSource` calls become
authenticated without any JavaScript patching.

The password is a random 48-hex-char secret generated once per install and kept in
`SharedPreferences` (`oc_server.password`), also exported to the server as
`OPENCODE_SERVER_PASSWORD`.

Other app changes on this branch:

- `ServerService` spawns `serve` with v2-compatible flags and keeps the patched-libc resolver
  fix from the v1 work (`docs/STANDALONE-NETWORK.md`);
- `Installer.STAMP = payload-v3` so an existing install re-extracts the new payload;
- `ocstandalone/stage-assets-v2.ps1` stages the v2 binary reproducibly from npm.

## 4. Build (offline-verified, device test pending)

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME    = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:KS_ALIAS='opencode'; $env:KS_PASS='opencode'

# once: glibc runtime from the v1.4 release asset + v2 binary from npm
.\ocstandalone\stage-assets-v2.ps1 -FromApk ..\opencode-android-v1.4.apk

powershell -ExecutionPolicy Bypass -File .\ocstandalone\build.ps1
adb install -r .\ocstandalone\build\opencode-solo.apk
```

Latest build: **114 751 180 bytes**, SHA-256
`CC90EE85C931E97BFE165A92C39AD432F84329220F3FD988D62D7E169BBAC86A`.
Offline checks: signed, `classes.dex` contains `AuthProxy`, payload `assets/opencode-bin` is the
236 MB v2 binary.

## 5. On-device test checklist (next time the phone is free)

1. Install over the existing app (`adb install -r`); data is kept and re-extracted (`payload-v3`).
2. `adb logcat -s ocstandalone:*` must show:
   - `auth proxy listening on 127.0.0.1:4097`,
   - `server listening on http://127.0.0.1:4096`,
   - `load http://127.0.0.1:4097/` then `page finished`.
3. The v2 UI must render **data**, not just the shell: projects/workspace list loads.
4. Server log must show `/api/info`, `/api/session`, `/api/event` → **200** (not 401).
5. Start a chat with a free model; it must answer.
6. If OK: bump the app to 1.5, tag a **pre-release** with this APK, and note v1 stays at 1.4.
7. If the UI shows CORS/Host errors instead, the proxy likely needs to rewrite `Host`/`Origin`
   to `127.0.0.1:4096` — that is the first knob to try.

## 6. Open questions

- Does the v2 UI use WebSocket (not just SSE)? The proxy tunnels upgrades after the handshake,
  but that path is untested.
- v2 migrated the existing v1 data directory on first start (`database migration ...` in
  logcat). Keep a backup of a working v1 install's data if a downgrade must be tested.
- App versioning: the v2 upgrade should become app **1.5** once verified; the v1.4 release stays
  the stable fallback.
