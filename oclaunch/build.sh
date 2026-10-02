#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
OUT="$ROOT/build"
AJ="${ANDROID_JAR:-$HOME/build/sdk/android.jar}"
KS="$OUT/launcher.keystore"
KS_ALIAS="${KS_ALIAS:?set KS_ALIAS to your keystore alias}"
KS_PASS="${KS_PASS:?set KS_PASS to your keystore password}"

if [ ! -f "$AJ" ]; then
	echo "error: android.jar not found at $AJ" >&2
	echo "fetch it with:" >&2
	echo "  curl -L -o p35.zip https://dl.google.com/android/repository/platform-35_r01.zip" >&2
	echo "  unzip -j p35.zip 'android-*/android.jar' -d \$HOME/build/sdk && rm p35.zip" >&2
	exit 1
fi

if [ ! -f "$KS" ]; then
	echo "error: keystore not found at $KS" >&2
	exit 1
fi

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex"
rm -f "$OUT/res.zip" "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/opencode-launcher.apk" "$OUT/opencode-launcher.apk.idsig"

echo "==> aapt2 compile"
aapt2 compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "==> aapt2 link"
aapt2 link -o "$OUT/base.apk" -I "$AJ" \
	--manifest "$ROOT/AndroidManifest.xml" \
	--java "$OUT/gen" \
	--min-sdk-version 24 \
	--target-sdk-version 35 \
	--auto-add-overlay \
	"$OUT/res.zip"

echo "==> javac"
javac -classpath "$AJ" -source 8 -target 8 -nowarn -d "$OUT/classes" \
	"$ROOT/src/app/opencode/launcher/MainActivity.java" \
	"$OUT/gen/app/opencode/launcher/R.java"

echo "==> d8"
mkdir -p "$OUT/dex"
d8 --lib "$AJ" --min-api 24 --output "$OUT/dex" "$OUT"/classes/app/opencode/launcher/*.class

echo "==> package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
jar uf "$OUT/unsigned.apk" -C "$OUT/dex" classes.dex

echo "==> sign"
apksigner sign \
	--ks "$KS" \
	--ks-key-alias "$KS_ALIAS" \
	--ks-pass "pass:$KS_PASS" \
	--key-pass "pass:$KS_PASS" \
	--v4-signing-enabled true \
	--out "$OUT/opencode-launcher.apk" \
	"$OUT/unsigned.apk"

echo "==> verify"
apksigner verify "$OUT/opencode-launcher.apk"
BADGING="$(aapt2 dump badging "$OUT/opencode-launcher.apk")"
echo "${BADGING%%$'\n'*}"

echo "OK: $OUT/opencode-launcher.apk"
