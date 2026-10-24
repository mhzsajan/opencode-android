# Why the standalone server cannot reach the network

Status: **open, narrowed down.** Last updated 2026-10-24 while the phone was unavailable,
so the conclusions below are from static analysis plus the measurements the previous session
saved on the device. Nothing here has been re-verified on hardware yet.

`ocstandalone` starts the bundled opencode binary from the app's own UID. The server answers
HTTP on `127.0.0.1:4096` (the WebView loads fine), but every upstream model call fails with
`Cannot connect to API: Was there a typo in the url or port?` after ~60 s. The *same* binary,
run from Termux, answers a real prompt in ~6 s.

## 1. Measurements collected on the phone (2026-10-05/06)

The previous session ran the bundled `ld-linux-aarch64.so.1 opencode-bin serve ...` from
Termux with a one-variable-per-run wrapper (`~/tst/t.sh`) and captured the API responses.
The raw evidence is the `handoff/` folder on the phone (`/sdcard/opencode/handoff/`).

| Run | UID / env | Result |
|---|---|---|
| A, port 4196 | Termux UID, `env -i` + only the app's own variables (no resolver override) | **worked**: assistant replied `pong`, 8 567 tokens, 3 s (`result-4196.json`) |
| B, port 4197 | Termux UID, same + `RESOLV_CONF`, `SSL_CERT_FILE`, `NODE_EXTRA_CA_CERTS` | **worked**: `pong` (`result-4197.json`) |
| v1.3 | app UID, generated resolv.conf pointing at 8.8.8.8/1.1.1.1 | chat failed ~69 s; the v1.4 source comment records a connection to `4801:2004:4148::443`, described as "a wrong host" |
| v1.4 | app UID, no resolver override, `DNS_RESULT_ORDER=ipv6first`, `DNS_AUTO_FALLBACK=1` | not measured. The phone was not tested after this build. |

Two things stand out:

1. **Run A used no resolver override at all and still worked.** So "the app needs a resolver
   override" was never established; the difference between the working and failing runs is the
   *process context* (Termux UID + Termux's filesystem), not the environment variables.
2. The only app-UID measurement (v1.3) is a single observation whose "wrong host" was never
   confirmed to be the thing that broke the request.

There was also a raw TCP probe table from Termux UID (`t4out.txt`):

```
172.65.90.20:443  FAIL      104.16.0.1:443  OK
172.65.90.22:443  FAIL      1.1.1.1:443     OK
                            172.65.90.20:80 OK
```

That probe was not reproducible from the saved scripts (the tool used is not recorded), so treat
it as a hint, not proof: it looks like something on the network path filters 443 to those
particular Cloudflare edges, not like a DNS problem.

Termux's own resolver state at the time (`tresolv.txt`):

```
$PREFIX/etc/resolv.conf = nameserver 8.8.8.8 / 8.8.4.4
RESOLV_CONF  unset
RES_OPTIONS  unset
```

## 2. Static analysis of the payload (2026-10-24)

The payload that ships in the APK is Termux's glibc build, so it was compiled with Termux's
filesystem layout baked in:

* `assets/lib/libc.so.6` contains **one** compiled-in resolver path, at file offset
  `0x16D4B0` (1 496 240):

  ```
  /data/data/com.termux/files/usr/glibc/etc/resolv.conf
  ```

  That directory only exists for the Termux UID. Under `app.opencode.standalone` it does not
  exist, so glibc's `res_init()` has no nameserver to use.

* `assets/opencode-bin` contains **zero** occurrences of `RESOLV_CONF`, `DNS_RESULT_ORDER` or
  `DNS_AUTO_FALLBACK`. Those variables are not part of the Bun runtime the binary embeds.
  **The `NET_ENV` entries in `MainActivity`/`ServerService` are no-ops.** The v1.3 idea of
  "DNS_RESULT_ORDER=ipv6first fixes the IPv4 blackhole" cannot have had any effect.

* `assets/opencode-bin` contains `/etc/resolv.conf` at offsets 502 820 and 121 294 432
  (embedded runtime / bundled JS). If any of those code paths resolve names itself, the app
  cannot satisfy them either: there is no writable `/etc/resolv.conf` on Android.

So the most likely root cause is simply **the child process has no resolver configuration**:
glibc looks in Termux's prefix, and the binary's own runtime (if it resolves independently)
looks in `/etc`. Termux works because `$PREFIX/glibc/etc/resolv.conf` exists there.

That also explains why v1.4 "dropping the injected resolver" changed nothing and could not have
changed anything: with or without `RESOLV_CONF`, the resolver never looked at the file.

## 3. Candidate fix (not implemented — needs the phone to validate)

Patch the compiled-in path in the bundled libc to a file the app can write:

| | |
|---|---|
| old string | `/data/data/com.termux/files/usr/glibc/etc/resolv.conf` (53 chars, NUL-padded) |
| new string | `/data/data/app.opencode.standalone/files/resolv.conf` (52 chars) |
| byte offset | `0x16D4B0` in `assets/lib/libc.so.6`, exactly one occurrence |

`/data/data/...` is the same inode as `filesDir` (`/data/user/0/...`), and the app is allowed to
access its own directory through it.

Then `ServerService` must write that file before spawning, from the live network plus public
fallbacks:

```
nameserver <each ConnectivityManager LinkProperties DnsServer>
nameserver 8.8.8.8
nameserver 1.1.1.1
```

`Installer.STAMP` has to be bumped (`payload-v2`) so an already-installed app re-extracts the
patched lib.

Risk: if the failing resolver is not glibc's but the binary's own `/etc/resolv.conf` path, this
patch will not help. That is why it has to be tested with a DNS probe before being merged.

## 4. Next experiments, in order

1. Install v1.4 (already built) and start it. In `adb logcat -s ocstandalone:*`, capture the
   server's own error line (the `srv|` mirror) for one chat request, with a provider key set.
2. Build a `debuggable` variant and, as the app UID (`run-as app.opencode.standalone`), resolve
   `opencode.ai` with a small test harness to see whether bionic/netd can resolve at all under
   that UID. Do the same with `ld.so opencode-bin` plus a hand-written resolv.conf in the app
   files dir to prove the file is being read.
3. Only then apply the libc patch + `ServerService` writer, rebuild, and repeat step 1.
4. If the binary's own resolver is what fails, the fallback plan is to run the child under a
   private root (`proot`) with a writable `/etc/resolv.conf`, or to patch the binary's
   `/etc/resolv.conf` to a short app-writable path (17 chars is very tight — this is probably
   not viable, so test it last).

## 5. What is *not* the problem

* **Not** cleartext HTTP: the UI loads from `http://127.0.0.1:4096` with the shipped
  `network_security_config.xml`; that works.
* **Not** a missing permission: `INTERNET` and `ACCESS_NETWORK_STATE` are granted.
* **Not** the `targetSdk 28` / W^X situation: the binary executes; only its outbound
  connections fail.
* **Not** the provider key: a missing key gives a different error, and the failing request never
  reaches the provider.
