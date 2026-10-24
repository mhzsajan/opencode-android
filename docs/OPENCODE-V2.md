# opencode v2 on Android

Updated 2026-10-24. Merged into `master` at `9422ba5` and shipped as the pre-release
**[v1.5](https://github.com/mhzsajan/opencode-android/releases/tag/v1.5)**; the v1.4 release
(opencode 1.18.34) remains the stable fallback.

**Status: verified on the phone and released, 2026-10-24.** The auth blocker is fixed and the
on-device checklist in section 5 passed end to end — proxy 200s, UI renders real data, a free
model answered. Shipped as `versionCode 6` / `versionName 1.5`. **The release is arm64 only**
(section 6): the bundled payload is aarch64, so it does not serve on the x86_64 emulator yet.

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

## 4. Build (verified on device)

The SDK now lives on **`G:\dev\android-sdk`** (it was previously at
`$env:LOCALAPPDATA\Android\Sdk`; `ANDROID_HOME` pointed at a nonexistent `G:\dev\android-sdk`
for a while, which is why anything relying on the env var failed). See
`docs/DEV-ENVIRONMENT.md` for the full layout.

```powershell
$env:ANDROID_HOME = 'G:\dev\android-sdk'
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

## 5. On-device test: passed (SM-S908E, 2026-10-24 20:32)

Installed over the existing app with `adb install -r` → `Success`; data kept, payload
re-extracted (`payload-v3`). The tested build was the code-5 build of 2026-10-24 20:32; the
released artifact is the same code rebuilt as `versionCode=6` / `versionName=1.5`
(`targetSdk=28`, signer `84:E7:50:…:10:6C:31`, same 114 751 180 bytes).

| check | result |
|---|---|
| `auth proxy listening on 127.0.0.1:4097` | yes |
| `server listening on http://127.0.0.1:4096` | yes |
| `page finished: http://127.0.0.1:4097/…/session/ses_…` | yes |
| any `401` in logcat | **none** |
| UI renders data, not just a shell | yes — session list, model picker, prior turns |
| free model answers | yes — **MiMo-V2.6-Flash Free** replied `Hi` |

The decisive check is the proxy, and it is cheap to run from the PC over `adb forward`:

```
tcp:4097  through AuthProxy, no auth sent   /api/info → 200  {"version":"2.1.4",...}
tcp:4096  direct, no auth                   /api/info → 401
```

Through the proxy: `/api/info` 200, `/api/session` 200, `/api/event` 200, `/api/config` 200,
`/api/agent` 200 (8 KB), `/api/project` 200. The 200/401 **pair** is what proves the proxy is
supplying the credentials, rather than a cached cookie making the UI look fine.

**Released 2026-10-24** as `versionCode 6` / `versionName 1.5`, pre-release tag
**[v1.5](https://github.com/mhzsajan/opencode-android/releases/tag/v1.5)** —
`opencode-solo.apk`, 114 751 180 bytes, SHA-256
`1CAF406246EB9A7E5592A6CC3311C20DB8BA1FB0AA7F40E911CB6E0CFBF79344`. Same signing key as v1.4,
so it updates in place. The release notes say plainly that it is arm64 only.

## 6. Architecture: arm64 phone, x86_64 emulator

The APK contains **no native libs** — it is pure Java. The only arch-bound parts are
`assets/opencode-bin` and its bundled glibc loader/libs. Two consequences:

- **An arm64 emulator cannot run on this host.** `system-images;android-37.0;default;arm64-v8a`
  installs cleanly (1 950 MB) and `avdmanager` creates the AVD, but the emulator exits in under
  two seconds:

  ```
  FATAL | Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on x86_64 host.
          System image must match the host architecture.
  ```

  There is no cross-architecture TCG fallback on Windows, so `Pixel_10_ARM64_API_37` is inert.
- **On the x86_64 image the arm64 payload does load and execute — but cannot serve.** This
  contradicts the earlier assumption that `libndk_translation` never handles `execve()`; measured
  on `emulator-5554` (API 37, `ro.product.cpu.abilist=x86_64,arm64-v8a`,
  `ro.dalvik.vm.native.bridge=libndk_translation.so`):

  | step | result |
  |---|---|
  | `ld-linux-aarch64.so.1 --version` | runs (audit log grants `execute` on the app-data file) |
  | `ld … ./opencode-bin --version` | **fails**: `opencode-bin: error while loading shared libraries: libc.so.6` |
  | `ld --library-path <dir> ./opencode-bin --version` | **works** → `opencode v2.1.4`, Bun banner `Bun v1.4.4 … Linux arm64`, prints stats |
  | same, then continue to `serve` | `panic: Unexpected futex_wait() return code: -2 - ENOENT` → `Bun has crashed` |

  Two facts worth keeping:
  - **`LD_LIBRARY_PATH` is invisible to the translated guest**, even though the native loader
    honours `--library-path`. This also explains the app's own `libc.so.6: cannot open shared
    object file` loop on the emulator, which is not a missing file.
  - **Translated arm64 is a dead end for the server.** Bun's runtime needs a working futex; the
    translator does not provide one. A native x86_64 payload is therefore required, not optional.

So payload testing needs the physical phone (arm64); UI/Java work can use `Pixel_10_API_37`
(x86_64, API 37). To make the emulator a full dev target, stage the x86_64 payload —
`@opencode/cli-linux-x64@2.1.4` exists on npm and is **240 416 944 bytes** unpacked
(`0x3e` ELF, verified), the same Bun single-file shape as the arm64 package. What that needs:

1. `assets/opencode-bin` ← the x64 binary;
2. `assets/lib/` ← x86_64 glibc (`ld-linux-x86-64.so.2`, `libc.so.6`, `libm`, `libdl`,
   `libpthread`) taken from an Ubuntu/Debian base rootfs, **not** the host's Windows DLLs;
3. the resolver patch re-applied to the new `libc.so.6`;
4. `Installer.java` hardcodes `ld-linux-aarch64.so.1` in two places (the lib list and the loader
   getter), so the loader name has to be chosen from `Build.SUPPORTED_ABIS` instead of spelled
   literally; `ServerService.launch()` should also pass `--library-path` explicitly instead of
   relying on `LD_LIBRARY_PATH` (see above).

**The resolver patch is the live blocker for x64.** Ubuntu 24.04's glibc 2.39 has
`/etc/resolv.conf` as a 16-byte slot (15 chars + NUL) followed immediately by `LOCALDOMAIN`, and
the ARM build's trick — rewriting a long compiled-in Termux path to
`/data/data/app.opencode.standalone/files/resolv.conf` (53 chars) — only works because Termux's
slot is 48+ bytes. A 53-char absolute path cannot fit in 16 bytes, so the x64 build needs either a
shorter path (≤15 chars, which no app-writable location has) or a **relative** path such as
`resolv.conf`, which works only if the child's cwd is the directory holding the file — and the
child's cwd is currently the user's project directory, which is not app-private.

A dual-arch APK would carry ~490 MB of payload, so prefer **two payload variants** over one fat
APK. Whether a native glibc x86_64 ELF executes under the emulator is still unproven, but the
mechanism is the normal one (`targetSdk 28` permits `execve()` of app-data files, the kernel ABI
is plain Linux x86_64, and no translation layer is involved).

## 7. Open questions

- Does the v2 UI use WebSocket (not just SSE)? The proxy tunnels upgrades after the handshake,
  but that path is untested.
- v2 migrated the existing v1 data directory on first start (`database migration ...` in
  logcat). Keep a backup of a working v1 install's data if a downgrade must be tested.
- App versioning: **1.5 (code 6) is released** as a pre-release; the v1.4 release stays the
  stable fallback. The next build carries the x86_64 payload and should be 1.6 (code 7).
- Can a native glibc x86_64 payload serve under the x86_64 emulator? Believed yes — it is plain
  Linux x86_64 with no translation layer, and `targetSdk 28` permits `execve()` of app-data
  files — but unproven. The open sub-problem is the **resolver path** (section 6): Ubuntu
  glibc's 16-byte slot cannot hold an app-private absolute path, so it needs a relative path plus
  a controlled cwd, or a glibc build whose compiled-in path is long enough to rewrite.
- Is `Bun`'s DNS via glibc `getaddrinfo` on x64 too (hence the resolver patch), or does the x64
  build use its own resolver? The arm64 build demonstrably needed the patch; do not assume x64
  behaves the same without testing a real hostname lookup.
