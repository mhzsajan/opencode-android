# The standalone server could not reach the network — fixed

Status: **fixed and verified on the phone on 2026-10-24.** This document keeps the
measurements that led there, the fix itself, and the evidence it works.

## 1. Symptom

`ocstandalone` started the bundled opencode binary from the app's own UID. The server answered
HTTP on `127.0.0.1:4096` (the WebView loaded fine) and the phone's own connectivity was fine, but
every upstream call failed:

```
level=ERROR message="Failed to fetch models.dev"
  cause="Transport error (GET https://models.opencode.ai/api.json)
  (cause: Error: Unable to connect. Is the computer able to access the url?)"
```

A real chat request then failed the same way, after ~60 s, with
`Cannot connect to API: Was there a typo in the url or port?`.
The *same* binary, started from Termux, worked (a prompt answered in ~6 s in the 2026-10-05
measurements).

## 2. Measurements that ruled things out

The previous session ran the bundled `ld-linux-aarch64.so.1 opencode-bin serve ...` from Termux
with a one-variable-per-run wrapper (`~/tst/t.sh`) and captured the API responses. The raw
evidence is in the phone's `handoff/` folder (`/sdcard/opencode/handoff/`).

| Run | UID / env | Result |
|---|---|---|
| A, port 4196 | Termux UID, `env -i` + only the app's own variables (no resolver override) | **worked**: assistant replied `pong`, 8 567 tokens, 3 s (`result-4196.json`) |
| B, port 4197 | Termux UID, same + `RESOLV_CONF`, `SSL_CERT_FILE`, `NODE_EXTRA_CA_CERTS` | **worked**: `pong` (`result-4197.json`) |
| v1.3 | app UID, generated resolv.conf pointing at 8.8.8.8/1.1.1.1 | chat failed ~69 s |
| v1.4 | app UID, no resolver override, `DNS_RESULT_ORDER`/`DNS_AUTO_FALLBACK` | **failed**: "Failed to fetch models.dev / Unable to connect" (reproduced 2026-10-24) |

Run A used **no** resolver override and still worked, so the earlier theory that the app needed a
resolver override was never supported by the data.

## 3. Root cause

The payload is Termux's glibc build, so glibc was compiled with Termux's filesystem layout baked
in:

* `assets/lib/libc.so.6` has exactly one compiled-in resolver path, at file offset
  `0x16D4B0` (1 496 240):

  ```
  /data/data/com.termux/files/usr/glibc/etc/resolv.conf
  ```

  That directory only exists under the Termux UID. For `app.opencode.standalone` it does not
  exist, so glibc's `res_init()` found no nameserver and every lookup failed. Termux worked
  because that file exists there (`nameserver 8.8.8.8 / 8.8.4.4`).

* `assets/opencode-bin` contains **zero** occurrences of `RESOLV_CONF`, `DNS_RESULT_ORDER` or
  `DNS_AUTO_FALLBACK`, so the `NET_ENV` entries in v1.3/v1.4 could never have had any effect.

* `assets/opencode-bin` contains `/etc/resolv.conf` at offsets 502 820 and 121 294 432, but
  neither matters once glibc has a usable nameserver list.

DNS poisoning / IPv4 blackholes were an artefact of the earlier, broken configuration; they are
not the cause.

## 4. The fix (implemented, in the repo)

1. **`ocstandalone/build.ps1` patches the bundled libc before packaging.** The single
   `/data/data/com.termux/files/usr/glibc/etc/resolv.conf` string is replaced in place with

   ```
   /data/data/app.opencode.standalone/files/resolv.conf
   ```

   (52 characters, fits the original 53-byte slot; the step is idempotent).
2. **`ServerService` writes that file before spawning the server.** It collects the live DNS
   servers from `ConnectivityManager`/`LinkProperties` (all networks), appends `8.8.8.8` and
   `1.1.1.1` as fallbacks, and writes `nameserver` lines plus `options timeout:5 attempts:2`.
   The resulting file is logged (`resolv.conf -> ...`) so it is visible in logcat.
3. **`Installer.STAMP` bumped to `payload-v2`** so an app that already extracted the old payload
   replaces it with the patched libc on the next launch.
4. The dead `DNS_RESULT_ORDER` / `DNS_AUTO_FALLBACK` env variables were removed from both
   activities.

`/data/data/app.opencode.standalone/files` is the same directory as `filesDir`
(`/data/user/0/...`); the path is only used to fit the patched string in place.

## 5. Verification on the phone (2026-10-24, SM-S908E / Android 16)

Before the fix (v1.4 unpatched): `Failed to fetch models.dev ... Unable to connect` on every
server start, and chat failed.

After the fix, with no Termux installed/used and no API key:

* logcat shows `resolv.conf -> nameserver 202.70.64.224 | ... | nameserver 8.8.8.8 | ...`
  and **no** `models.dev` error on startup;
* `GET http://127.0.0.1:4096/provider` returns the live catalog (6.5 MB of JSON);
* `POST /session` creates a session rooted at `/storage/emulated/0/OpenCode`;
* `POST /session/<id>/message` with model `opencode/nemotron-3.5-lightning-free` returned a
  complete assistant message containing `pong` (8 570 tokens, `finish: stop`);
* the WebView UI chat works the same way — a session titled "Greeting" answered
  "opencode/big-pickle" to "What model are you?", with free models selectable in the model menu.

Screenshot: `shots/06-chat.png` in the DSH workspace (`opencode-android` is the repo).

## 6. What is *not* the problem

* **Not** cleartext HTTP: the UI loads over `http://127.0.0.1:4096` with the shipped
  `network_security_config.xml`.
* **Not** a missing permission: `INTERNET` and `ACCESS_NETWORK_STATE` are granted.
* **Not** the `targetSdk 28` / W^X situation: the binary runs; only its resolution was broken.
* **Not** the provider key: free models answer without one (see below).
* **Not** asset compression: assets are added with explicit forward-slash names
  (`assets/lib/...`), see the packaging note in `build.ps1`; `aapt2 -A` on Windows wrote
  backslashes into entry names and broke `AssetManager.open("lib/...")` (fixed the same day).

## 7. Remaining notes

* Free models on the `opencode` provider work with no key. A key is still needed for paid
  providers; the app's setup screen writes `auth.json` to the server's `HOME`.
* Some free models come back `Upstream request failed: Model is unavailable.` — that is the
  provider's model availability, not networking.
* If the opencode payload is ever replaced by a different build, re-check the libc offset and
  string before assuming the patch still applies (`build.ps1` fails loudly if the old string is
  missing).
