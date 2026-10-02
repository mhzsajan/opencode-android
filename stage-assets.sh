#!/data/data/com.termux/files/usr/bin/bash
# Populates ocstandalone/assets/ with the two things too large to commit:
#   1. the opencode binary (177 MB)
#   2. a minimal glibc runtime (3.8 MB) to run it on Android
#
# The binary is either copied from a local path or downloaded from the
# official release. The glibc files are copied from Termux's glibc package,
# which is the only glibc build known to work on Android.
set -euo pipefail

ROOT="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
ASSETS="$ROOT/ocstandalone/assets"
OPENCODE_REPO="${OPENCODE_REPO:-anomalyco/opencode}"
LOCAL_BIN="${1:-$HOME/.opencode/bin/opencode-bin}"

mkdir -p "$ASSETS/lib"

if [ -f "$ASSETS/opencode-bin" ] && [ "${OPENCODE_FORCE:-0}" != "1" ]; then
	echo "assets/opencode-bin already present (OPENCODE_FORCE=1 to refetch)"
else
	if [ -f "$LOCAL_BIN" ]; then
		echo "==> copying binary from $LOCAL_BIN"
		cp "$LOCAL_BIN" "$ASSETS/opencode-bin"
	else
		echo "==> resolving latest release of $OPENCODE_REPO"
		VER="$(curl -fsSL "https://api.github.com/repos/$OPENCODE_REPO/releases/latest" \
			| grep -oE '"tag_name": *"[^"]+"' \
			| head -1 | sed -E 's/.*"tag_name": *"([^"]+)"/\1/')"
		[ -n "$VER" ] || { echo "error: could not resolve a release" >&2; exit 1; }
		URL="https://github.com/$OPENCODE_REPO/releases/download/$VER/opencode-linux-arm64.tar.gz"
		echo "==> downloading $URL"
		curl -fL "$URL" -o "$TMPDIR/opencode.tar.gz"
		echo "==> extracting"
		tar -xzf "$TMPDIR/opencode.tar.gz" -C "$TMPDIR"
		rm -f "$TMPDIR/opencode.tar.gz"
		cp "$TMPDIR/opencode-bin" "$ASSETS/opencode-bin"
		rm -f "$TMPDIR/opencode-bin"
	fi
fi

echo "==> copying minimal glibc from $PREFIX/glibc/lib"
for f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 libpthread.so.0 libdl.so.2; do
	cp "$PREFIX/glibc/lib/$f" "$ASSETS/lib/$f"
done

chmod 755 "$ASSETS/opencode-bin" "$ASSETS/lib"/*

echo
echo "assets staged:"
du -sh "$ASSETS/opencode-bin" "$ASSETS/lib"
echo
echo "next: KS_ALIAS=... KS_PASS=... ./ocstandalone/build.sh"
