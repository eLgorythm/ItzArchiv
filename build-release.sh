#!/usr/bin/env bash
# Build APK release teroptimasi (R8) + ditandatangani keystore release.
# Butuh: JDK 17, Android SDK (platform 34 + build-tools 35.0.0), tools/r8.jar, release keystore.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/home/hatch/tools/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-/home/hatch/android-sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/build-tools/35.0.0:$PATH"

ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
SRC="app/src/main"
OUT="build-release"
KS="release/itzarchiv-release.keystore"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" libs

CP_LIBS=$(printf ':%s' libs/*.jar); CP_LIBS=${CP_LIBS:1}

echo "==> aapt2 compile resources"
"$BUILD_TOOLS/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled-res.zip"

echo "==> aapt2 link"
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="me.fndlabs.itzarchiv">|' \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BUILD_TOOLS/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" --java "$OUT/gen" \
  --version-code 10 --version-name "1.9" \
  --min-sdk-version 26 --target-sdk-version 34 \
  "$OUT/compiled-res.zip"

echo "==> javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_JAR:$CP_LIBS" -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> R8 (shrink + optimize)"
if [ ! -f tools/r8.jar ]; then
  mkdir -p tools
  curl -sL -o tools/r8.jar https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar
fi
(cd "$OUT/classes" && "$JAVA_HOME/bin/jar" cf "../program.jar" .)
java -cp tools/r8.jar com.android.tools.r8.R8 \
  --release --min-api 26 \
  --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  --pg-conf app/proguard-rules.pro \
  "$OUT/program.jar" libs/*.jar

echo "==> masukin dex ke APK"
cp "$OUT/app-unsigned.apk" "$OUT/app-with-dex.apk"
cd "$OUT" && zip -q app-with-dex.apk -j dex/classes.dex && cd ..

echo "==> zipalign"
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/app-with-dex.apk" "$OUT/app-aligned.apk"

echo "==> tanda tangan release"
# Password dibaca dari file info di ~/workspace/your_files (tidak ditulis ke log)
export KEYSTORE_PASS="$(grep 'Store password:' "$HOME/workspace/your_files/itzarchiv-release-key-info.txt" | sed 's/.*Store password:[[:space:]]*//')"
[ -n "$KEYSTORE_PASS" ] || { echo "Password keystore tidak ditemukan"; exit 1; }
"$BUILD_TOOLS/apksigner" sign --ks "$KS" --ks-key-alias itzarchiv \
  --ks-pass env:KEYSTORE_PASS --key-pass env:KEYSTORE_PASS \
  --out "$OUT/ItzArchiv-release.apk" "$OUT/app-aligned.apk"
unset KEYSTORE_PASS

"$BUILD_TOOLS/apksigner" verify --print-certs "$OUT/ItzArchiv-release.apk" | grep -E "Signer #1 certificate (DN|SHA-256)" || true
echo ""
echo "BERES: $OUT/ItzArchiv-release.apk"
ls -lh "$OUT/ItzArchiv-release.apk"
