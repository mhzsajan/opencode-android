# opencode v2 on Android — work in progress

Updated 2026-10-24. **Branch `v2`.** `master` still ships the verified v1 engine
(opencode 1.18.34 in v1.4); this document tracks the upgrade to opencode v2.

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

## 3. Current state in the app

Working:

- `stage-assets-v2.ps1` stages the v2 binary from npm (libs come from a v1 APK or from the
  already-staged `assets/lib`);
- `ServerService` spawns `serve` with the v2-compatible flags, keeps the patched-libc
  resolver fix, and sets `OPENCODE_SERVER_PASSWORD` to a random per-install secret stored in
  `SharedPreferences` (`oc_server.password`);
- `Installer.STAMP` is `payload-v3`, so an existing install re-extracts the new payload;
- the v2 server starts inside the app, the WebView loads `/`, and the v2 UI boots
  (`page finished`, `[global-sdk] event stream disconnected` from the UI).

**Blocker:** the UI's own API calls still get `401`. The app answers the challenge with
`WebViewClient.onReceivedHttpAuthRequest` and also pre-seeds
`WebView.setHttpAuthUsernamePassword(...)`, but Chromium does not apply either to the UI's
`fetch`/`XHR`/`EventSource` requests on `/api/*` (server log shows `/api/event`, `/api/info`,
`/api/session/...` → 401). The UI therefore renders its shell without data.

## 4. Next step: loopback auth proxy

The clean fix is a tiny loopback proxy inside the app: WebView loads
`http://127.0.0.1:4097/`; the proxy forwards every request to the real server on
`127.0.0.1:4096`, adding `Authorization: Basic base64("opencode:" + password)`.
Because the proxy speaks plain HTTP and streams both directions, SSE (`/api/event`) and the
static UI work unchanged, and no JavaScript patching is needed. WebSocket upgrades (if the v2
UI uses any) can be tunneled after the first byte-forward.

Alternative (fragile): inject JS that wraps `fetch`/`XMLHttpRequest` and replaces `EventSource`
with a fetch-based SSE reader. Rejected unless the proxy turns out to be too heavy.

## 5. Reproduce the current build

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME    = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:KS_ALIAS='opencode'; $env:KS_PASS='opencode'

# once: grab the glibc runtime from the v1.4 release asset, then the v2 binary from npm
.\ocstandalone\stage-assets-v2.ps1 -FromApk ..\..\opencode-android-v1.4.apk

powershell -ExecutionPolicy Bypass -File .\ocstandalone\build.ps1
adb install -r .\ocstandalone\build\opencode-solo.apk
```

The resulting APK is ~115 MB (the v2 binary deflates less than v1's).

## 6. Open questions

- Does v2's `/api/event` stream need headers beyond Basic (e.g. `Accept: text/event-stream`)?
  The proxy passes requests through, so this only matters if the browser sets them.
- v2 migration: on first start it migrated the existing v1 data directory
  (`database migration ...` lines in logcat). Keep a backup of a working v1 install's data if a
  downgrade must be tested.
- App versioning: the v2 upgrade should become app **1.5** (or 2.0) once verified, with its own
  release; the v1.4 release stays the stable fallback.
