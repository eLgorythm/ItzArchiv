#!/usr/bin/env bash
# Build APK tanpa Gradle — buat lingkungan yang daemon Gradle-nya gak bisa jalan.
# Butuh: JDK 17, Android SDK (platform 34 + build-tools 34.0.0), curl (buat unduh libs kalau belum ada)
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/home/hatch/tools/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-/home/hatch/android-sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/build-tools/35.0.0:$PATH"

ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
SRC="app/src/main"
OUT="build-manual"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" libs

# --- unduh library kalau belum ada ---
declare -A LIB_URLS=(
  ["commons-compress-1.26.2.jar"]="https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.26.2/commons-compress-1.26.2.jar"
  ["xz-1.10.jar"]="https://repo1.maven.org/maven2/org/tukaani/xz/1.10/xz-1.10.jar"
  ["junrar-7.5.5.jar"]="https://repo1.maven.org/maven2/com/github/junrar/junrar/7.5.5/junrar-7.5.5.jar"
  ["commons-io-2.16.1.jar"]="https://repo1.maven.org/maven2/commons-io/commons-io/2.16.1/commons-io-2.16.1.jar"
  ["commons-lang3-3.14.0.jar"]="https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar"
  ["commons-codec-1.17.1.jar"]="https://repo1.maven.org/maven2/commons-codec/commons-codec/1.17.1/commons-codec-1.17.1.jar"
  ["slf4j-api-1.7.36.jar"]="https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"
  ["zip4j-2.11.5.jar"]="https://repo1.maven.org/maven2/net/lingala/zip4j/zip4j/2.11.5/zip4j-2.11.5.jar"
)
for f in "${!LIB_URLS[@]}"; do
  if [ ! -f "libs/$f" ]; then
    echo "Unduh $f"
    curl -L -o "libs/$f" "${LIB_URLS[$f]}"
  fi
done

CP_LIBS=$(printf ':%s' libs/*.jar)
CP_LIBS=${CP_LIBS:1}

echo "==> aapt2 compile resources"
"$BUILD_TOOLS/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled-res.zip"

echo "==> aapt2 link (bikin R.java + APK mentah)"
# aapt2 manual butuh atribut package di manifest (Gradle biasanya nyuntik dari namespace)
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="me.fndlabs.itzarchiv">|' \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BUILD_TOOLS/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --version-code 10 --version-name "1.9" \
  --min-sdk-version 26 --target-sdk-version 34 \
  "$OUT/compiled-res.zip"

echo "==> javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 17 -target 17 \
  -cp "$ANDROID_JAR:$CP_LIBS" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> d8 (jadi dex)"
"$BUILD_TOOLS/d8" --release --min-api 26 \
  --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') libs/*.jar

echo "==> masukin dex ke APK"
cp "$OUT/app-unsigned.apk" "$OUT/app-with-dex.apk"
cd "$OUT"
zip -q app-with-dex.apk -j dex/classes.dex
# zip -j bakal namain classes.dex aja di root? pastikan namanya classes.dex
cd ..

echo "==> zipalign"
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/app-with-dex.apk" "$OUT/app-aligned.apk"

echo "==> keystore debug (kalau belum ada)"
if [ ! -f "$OUT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$OUT/debug.keystore" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Itzli,C=ID" >/dev/null 2>&1
fi

echo "==> tanda tangan"
"$BUILD_TOOLS/apksigner" sign --ks "$OUT/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$OUT/ItzArchiv-debug.apk" "$OUT/app-aligned.apk"

"$BUILD_TOOLS/apksigner" verify --print-certs "$OUT/ItzArchiv-debug.apk" | head -5
echo ""
echo "BERES: $OUT/ItzArchiv-debug.apk"
ls -lh "$OUT/ItzArchiv-debug.apk"
