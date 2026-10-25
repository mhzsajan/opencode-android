# Contributing to opencode-android

Thanks for your interest. This project is a small, focused Android shell around
the [opencode](https://github.com/anomalyco/opencode) server. Contributions are
welcome, but please read this first.

## Before you start

- Open an issue first for anything larger than a small fix, so we can agree on
  the approach. Drive-by PRs that change behaviour without discussion are usually
  rejected.
- Check `docs/OPENCODE-V2.md` and `AGENTS.md` — they contain the hard-won facts
  about this codebase (seccomp, payloads, resolver patching, traps). Most
  "obvious" fixes have already been tried and failed; reading first saves time.

## Building

Payloads (the opencode binary + libc) are not committed (they exceed GitHub's
100 MB limit). Restore them from a released APK, then build:

```powershell
# arm64 (phones)
powershell -File .\ocstandalone\stage-assets-v2.ps1 -FromApk C:\path\opencode-solo.apk

# x86_64 (emulator)
powershell -File .\ocstandalone\stage-assets-x64.ps1 -FromApk C:\path\opencode-solo-x64.apk

$env:KS_ALIAS='<alias>'; $env:KS_PASS='<password>'
$env:ANDROID_HOME='...'; $env:GRADLE_USER_HOME='...'
powershell -ExecutionPolicy Bypass -File .\ocstandalone\build.ps1            # arm64
powershell -ExecutionPolicy Bypass -File .\ocstandalone\build.ps1 -Arch x64  # x86_64
```

Android Studio stays on `C:`; SDK/AVDs/Gradle live on `G:`. See
`docs/DEV-ENVIRONMENT.md`.

## Rules that will get your PR rejected

- **Never commit payloads, APKs, or the keystore.** They are gitignored for a
  reason (size + secrets). The release assets are the documented restore source.
- **Never pass Devanagari (or any non-ASCII) on a PowerShell command line** —
  it arrives as `????`. Drive such code from a node script. See `AGENTS.md` trap 2.
- **Never round-trip a non-ASCII file through `Get-Content` / `Set-Content`** —
  it double-encodes UTF-8. Use `edit`/`.NET WriteAllText`, UTF-8 without BOM.
- **Verify the file, not the exit code.** A render that finishes is not a render
  that is correct.
- **One variable per test.** Do not change two things and then guess which one
  mattered.

## Pull requests

- One logical change per PR. Include what you tested and on what device/emulator.
- If you touched `ocstandalone/src/...`, confirm the APK builds for **both**
  architectures (`build.ps1` and `build.ps1 -Arch x64`).
- Link the issue your PR closes.

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). Be kind.
