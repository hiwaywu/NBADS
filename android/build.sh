#!/usr/bin/env bash
# build.sh -- builds NBAHub.apk with the SDK tools directly (no Gradle), Linux/macOS.
#   ANDROID_SDK=/path/to/sdk ./android/build.sh         phone   -> NBAHub.apk
#   ANDROID_SDK=/path/to/sdk ./android/build.sh --tv    Fire TV -> NBAHub-TV.apk
# Needs: JDK 17+ (javac, jar, keytool), build-tools 36, platforms/android-35.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$here")"
sdk="${ANDROID_SDK:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
bt="${BUILD_TOOLS:-$sdk/build-tools/36.0.0}"
[ -d "$bt" ] || bt="$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1)"
jar="${ANDROID_JAR:-$sdk/platforms/android-35/android.jar}"
out="$here/build"
ver_code=6
ver_name=2.6
manifest="$here/AndroidManifest.xml"; apk="$here/NBAHub.apk"; rename=()
if [ "${1:-}" = "--tv" ]; then
  # same code, TV manifest (TV launcher, banner, no touchscreen), own package name
  manifest="$here/tv/AndroidManifest.xml"; apk="$here/NBAHub-TV.apk"
  rename=(--rename-manifest-package com.nbahub.tv)
fi

for f in "$bt/aapt2" "$bt/d8" "$bt/zipalign" "$bt/apksigner" "$jar"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

rm -rf "$out"
mkdir -p "$out/gen" "$out/classes" "$out/dex" "$out/assets"

echo "[1/7] assets: the PC page, hls.js and the channel list, bundled"
cp "$repo/nba/hub/index.html" "$repo/nba/hub/hls.min.js" "$repo/nba/hub/shaka.js" "$out/assets/"
cp "$repo/nba/feeds.txt" "$out/assets/"

echo "[2/7] compiling resources"
"$bt/aapt2" compile --dir "$here/res" -o "$out/res.zip"

echo "[3/7] linking resources + manifest"
"$bt/aapt2" link -o "$out/base.apk" -I "$jar" \
  --manifest "$manifest" -R "$out/res.zip" -A "$out/assets" \
  --java "$out/gen" --auto-add-overlay \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$ver_code" --version-name "$ver_name" ${rename[@]+"${rename[@]}"}

echo "[4/7] compiling java"
find "$here/src" "$out/gen" -name '*.java' > "$out/sources.txt"
javac -source 8 -target 8 -nowarn -encoding UTF-8 -Xlint:-options \
  -bootclasspath "$jar" -classpath "$jar" -d "$out/classes" @"$out/sources.txt"

echo "[5/7] dexing"
(cd "$out/classes" && jar cf "$out/classes.jar" .)
"$bt/d8" --lib "$jar" --min-api 24 --output "$out/dex" "$out/classes.jar"

echo "[6/7] packaging"
cp "$out/base.apk" "$out/unsigned.apk"
(cd "$out/dex" && zip -q -9 "$out/unsigned.apk" classes.dex)

echo "[7/7] aligning + signing"
ks="$here/debug.keystore"
if [ ! -f "$ks" ]; then
  keytool -genkeypair -keystore "$ks" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname 'CN=Android Debug,O=Android,C=US' >/dev/null 2>&1
fi
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$bt/apksigner" sign --ks "$ks" --ks-pass pass:android --key-pass pass:android \
  --out "$apk" "$out/aligned.apk"
"$bt/apksigner" verify "$apk"
echo
echo "OK -> $apk ($(( $(stat -c%s "$apk" 2>/dev/null || stat -f%z "$apk") / 1024 )) KB)"
