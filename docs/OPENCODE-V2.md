# opencode v2 on Android

Updated 2026-10-25. Merged into `master` at `9422ba5`, shipped as the pre-release
**[v1.5](https://github.com/mhzsajan/opencode-android/releases/tag/v1.5)** (arm64) and then
**[v1.6](https://github.com/mhzsajan/opencode-android/releases/tag/v1.6)** (arm64 **and** x86_64);
the v1.4 release (opencode 1.18.34) remains the stable fallback.

**Status: verified on the phone (v1.5, arm64) and on the x86_64 emulator (v1.6), 2026-10-24/25.**
The auth blocker is fixed and the on-device checklist in section 5 passed end to end — proxy 200s,
UI renders real data, a free model answered. v1.6 adds a second payload so the same app runs on the
emulator; getting there needed a native x86_64 build plus a ptrace shim for this app's seccomp
filter, which is what sections 6 and 7 are about.

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

## 6. Two payloads, one app

The APK still contains **no native libs of its own** — it is pure Java. Everything
architecture-bound lives in `assets/`: one `opencode-bin` plus the loader and libs it needs.
That is what makes two variants cheap: the Java is identical, the payload is not.

### 6.1 Why the x86_64 payload has to be a different build

- **An arm64 emulator cannot run on this host.** `system-images;android-37.0;default;arm64-v8a`
  installs cleanly (1 950 MB) and `avdmanager` creates the AVD, but the emulator exits in under
  two seconds:

  ```
  FATAL | Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on x86_64 host.
          System image must match the host architecture.
  ```

  There is no cross-architecture TCG fallback on Windows, so `Pixel_10_ARM64_API_37` is inert.
- **The arm64 payload does execute on the x86_64 image, under `libndk_translation` — but it
  cannot serve.** `ld-linux-aarch64.so.1 opencode-bin --version` prints `opencode v2.1.4`
  (`Bun v1.4.4 • Linux arm64`); continuing to `serve` produces
  `panic: Unexpected futex_wait() return code: -2 - ENOENT`, then `Bun has crashed`. So the
  translator is good enough to start Bun and not good enough to run it. A native x86_64 payload
  is required, not optional.

### 6.2 Which payload is installed is decided by the assets

Not by `Build.SUPPORTED_ABIS`. On this x86_64 image that list is
`x86_64,arm64-v8a` (native bridge), so an ABI check would pick the wrong loader for the payload
that is actually in the APK. `Installer` therefore asks the payload:

| method | how it decides |
|---|---|
| `loaderName()` | first `assets/lib/ld-*` entry, alphabetically |
| `isX64()` | `e_machine` of `assets/opencode-bin` — `0x3e` is x86_64, `0xb7` is aarch64 |
| `shim()` | presence of `assets/shim`, not the architecture |
| `isInstalled()` | stamp is `payload-v6/<loader>`, so switching variants reinstalls instead of trusting a stale payload |

`ServerService.launch()` builds argv from the same answers: `[shim] ld.so --library-path <lib>
opencode-bin serve …`. `--library-path` is passed explicitly as well as `LD_LIBRARY_PATH`
because a translated guest never sees the env var; the flag works either way.

### 6.3 The two payloads

| | arm64 (phones) | x86_64 (emulator) |
|---|---|---|
| npm package | `@opencode/cli-linux-arm64@2.1.4` | `@opencode/cli-linux-x64-musl@2.1.4` |
| `assets/opencode-bin` | 236 112 888 bytes, `aarch64` | 239 330 728 bytes, `x86_64` |
| libc | Termux glibc 2.38 | musl (Termux x86_64) |
| `assets/lib/` | `ld-linux-aarch64.so.1`, `libc.so.6`, `libm.so.6`, `libpthread.so.0`, `libdl.so.2` | `ld-musl-x86_64.so.1`, `libstdc++.so.6`, `libgcc_s.so.1` |
| launcher | the loader itself | `assets/shim` → loader |
| APK | `opencode-solo.apk`, 114 751 180 bytes, SHA-256 `518C86B212CE7C374AA2D2B9E8992962E4A648B1EE1CB314ED21ECD5139AE3FC` | `opencode-solo-x64.apk`, 114 407 041 bytes, SHA-256 `B1EAE1C9FC4B57D05750E28A0A8DBDE4AF50AEAC601E1AC34B385A9C51753428` |

Both are `versionCode 7` / `versionName 1.6`, `targetSdk 28`, **not debuggable**, same signer
`84:E7:50:…:10:6C:31`. Same `applicationId` and same `versionCode` means Android treats them as
the same app: switching variants on one device needs an uninstall first.

Staging is reproducible from a clone:

```powershell
powershell -File ocstandalone\stage-assets-v2.ps1  -FromApk <arm64 apk>   # npm + glibc libs
powershell -File ocstandalone\stage-assets-x64.ps1 -FromApk <x64 apk>     # npm + musl libs + shim
powershell -File ocstandalone\build.ps1 [-Arch x64]
```

### 6.4 The resolver, one more time

Both libcs have a **compiled-in** resolver path, and neither path exists in the app sandbox.
`build.ps1` rewrites one string per payload:

| payload | compiled in | rewritten to | why that shape |
|---|---|---|---|
| aarch64 glibc | `/data/data/com.termux/files/usr/glibc/etc/resolv.conf` (53-byte slot) | `/data/data/app.opencode.standalone/files/resolv.conf` (52 chars) | absolute is fine; the slot is long |
| x86_64 musl | `/etc/resolv.conf` (16-byte slot) | `.resolv.conf` | 53 chars cannot fit; a relative name is resolved against the child's cwd |

So `writeResolvConf()` writes `files/resolv.conf` always, and additionally
`<project>/.resolv.conf` when `isX64()` — `launch()` already sets the child's cwd to the project
directory. Verified from inside the payload's own context: `./.resolv.conf` is 87 bytes with the
live nameservers, and raw DNS to `10.0.2.3`/`8.8.8.8` returns answers.

An earlier plan used x86_64 **glibc** from an Ubuntu rootfs. It was abandoned: Ubuntu's 16-byte
`/etc/resolv.conf` slot forces the same relative-path trick, while its libs are larger and its
loader adds nothing. musl is the payload opencode actually publishes for this platform.

## 7. The x86_64 blocker: a seccomp filter that traps syscalls musl issues

This was the real failure, and it only appears once the resolver works. The app runs with
`targetSdk 28` (required: it is what permits `execve()` of an executable in app data), so it gets
the legacy `untrusted_app_27` SELinux domain and a seccomp filter derived from bionic's policy.
That filter was written for **bionic**, which never issues the pre-`*at` variants: `lstat`,
`mkdir`, `chmod`, `epoll_wait`, `poll`, `accept`, `dup2`, `inotify_init`, … A musl/Zig build does.
They are not killed with `EPERM`; they are **`SECCOMP_RET_TRAP`**ed, i.e. delivered as `SIGSYS`.
Bun treats that as a fatal signal and dies at startup, which looks exactly like the resolver bug
it replaced.

Measured with static probes installed into the app's own `filesDir` and run with the app's uid:

| syscall | result |
|---|---|
| `poll` — `nfds=0`, 1 fd/0 ms, 1 fd/20 ms | **SIGSYS every time** |
| `ppoll`, `epoll_pwait` | allowed |
| `epoll_wait` | **SIGSYS** |
| `ptrace(PTRACE_PEEKDATA)` | `-EFAULT` |
| `ptrace(PTRACE_POKEDATA)` | works |
| raw DNS to `10.0.2.3` / `8.8.8.8` | 3 answers each |
| TCP connect `1.1.1.1:443` | OK |

### 7.1 The shim

`shim/shim.c` is a **static, freestanding x86_64** tracer (5 728 bytes, SHA-256
`DB0F289C78FC697EE6D9EA4275AAFF55A5EAEF1A1EE76682B606239C168DE9C3`, `e_machine=0x3e`). It makes
raw syscalls only, so it needs no libc and is compiled on Windows.

It `PTRACE_TRACEME`s, `execve`s the loader with the original argv, and on each `SIGSYS`:

1. reads the trapped syscall number from `orig_rax`;
2. rewrites the argument registers for the `*at`/newer equivalent;
3. rewinds `rip` by 2 (undo `syscall`) and sets **both `rax` and `orig_rax`** to the new number;
4. suppresses the signal.

Translation table, all verified in place:

| trapped | becomes |
|---|---|
| `lstat` 6 | `newfstatat` 262 (with `AT_SYMLINK_NOFOLLOW`) |
| `access` 21 | `faccessat` 269 |
| `chmod` 90 | `fchmodat` 268 |
| `chown` 92 / `lchown` 94 | `fchownat` 260 |
| `mkdir` 83 | `mkdirat` 258 |
| `rename` 82 | `renameat` 264 |
| `unlink` 87 / `rmdir` 84 | `unlinkat` 263 |
| `link` 86 | `linkat` 265 |
| `symlink` 88 | `symlinkat` 266 |
| `mknod` 133 | `mknodat` 259 |
| `creat` 85 | `openat` 257 |
| `dup2` 33 | `dup3` 292 |
| `accept` 43 | `accept4` 288 |
| `epoll_create` 213 | `epoll_create1` 291 |
| `epoll_wait` 232 | `epoll_pwait` 281 |
| `inotify_init` 253 | `inotify_init1` 294 |
| `poll` 7 | `ppoll` 271 |

`poll` is the only one that cannot be done with registers alone: `ppoll` takes a `timespec*`
instead of milliseconds. The shim writes one into the tracee's stack. It goes at `rsp-512`
(`-256` and `-128` on retry), skipping the 128-byte red zone — and it must be written with
`POKEDATA`, because `PEEKDATA` returns `-EFAULT` under this filter.

Two properties that make this safe to ship: `PTRACE_O_EXITKILL` means the payload cannot outlive
the app, and the shim is **only** packaged with the x86_64 payload. The aarch64 payload was built
against Termux's glibc, makes no trapped calls, and keeps the direct-loader path — presence of
`assets/shim` selects the launch path, so that stays true without an architecture check.

### 7.2 End-to-end evidence

On `emulator-5554` (Pixel_10_API_37, x86_64, API 37, Play Store image, no root), installed from
the released x64 APK:

| check | result |
|---|---|
| first launch | Setup screen renders, 8 permission groups listed |
| server | `/api/info` → `{"version":"2.1.4","pid":32726,…}` through the auth proxy on 4097 |
| model call | `opencode` / `big-pickle`, no API key: reply `hello`, 3 511 in / 3 out, cost 0 |
| agent shell tool | `uname -m; id -u; echo SANDBOX_OK` → `x86_64`, `10243`, `SANDBOX_OK`, `exit: 0` |
| where it ran | as the app's uid, inside the sandbox — not the host |

The last row is the one that matters: model round-trip **and** process spawn **and** the tool
result coming back all work under the filter, through the shim. Captured in the app's own UI at
`docs/evidence/x64-emulator-shell-tool.png`.

Driving it from a PC needs no token — the proxy adds the credentials:

```
adb forward tcp:8099 tcp:4097
POST http://127.0.0.1:8099/api/session/<id>/prompt
     {"text":"…","model":{"providerID":"opencode","id":"big-pickle"}}
```

The body shape is not `{parts:[…]}` and not `{prompt:{…}}`; both are 400. The UI's own
`Prompt` schema is `{text, files?, agents?, skills?}`.

## 8. Known limits

- **models.dev times out on this host, and it is bandwidth, not a bug.** The log shows
  `Failed to fetch models.dev cause="Cause([Fail(TimeoutError: Operation timed out after '10s')])"`.
  `api.json` is 5 912 831 bytes and takes 11.98–12.57 s here (3 samples) against Bun's 10 s
  deadline. It happens with and without the shim, so it predates this work. The built-in
  `opencode` provider still answers; a provider whose catalogue lives only in models.dev may show
  an incomplete model list until the link is faster.
- **No arm64 device is available any more.** The arm64 artifact is released and was verified on
  the phone; further development happens on the emulator with the x64 variant.
- **One variant per install.** Same `versionCode`: `adb install` of the other variant fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` until the app is uninstalled.
- **The release build is not debuggable**, which is correct for shipping but removes `run-as`.
  Swapping `files/shim` for an experiment needs a debuggable build (and `am force-stop` first, or
  `cp` fails with `Text file busy`).
- **WebSocket upgrades are still untested.** `AuthProxy` tunnels the upgrade after the handshake;
  the UI so far only needed SSE.
- **v2 migrated the v1 data directory** on first start. Keep a copy of a working v1 install's data
  if a downgrade has to be tested.
- The bundled payload has **no git**; the agent's shell runs as the app's uid with the project
  directory on shared storage (`/storage/emulated/0/OpenCode`), so files written there persist and
  are visible over `adb`.
