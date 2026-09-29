#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/coding-tools-workspace/tools/android-sdk}"
PLATFORM="${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}"
TOOLS="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/35.0.0}"
KEYSTORE="${HOME}/.android/debug.keystore"

cd "$ROOT"
rm -rf classes dex classes.dex unsigned.apk aligned.apk MiTVHomeBridge.apk
mkdir -p classes dex "$(dirname "$KEYSTORE")"

if [[ ! -f "$PLATFORM" && -z "${ANDROID_JAR:-}" ]]; then
  PLATFORM="$SDK/platforms/android-36/android.jar"
fi
RESOURCE_APK="${ANDROID_FRAMEWORK_RES:-$PLATFORM}"
if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storepass android -alias androiddebugkey -keypass android \
    -dname 'CN=Android Debug,O=Android,C=US' \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null
fi

javac -encoding UTF-8 -source 8 -target 8 -classpath "$PLATFORM" -d classes \
  src/com/ucnacdx2/mitvhomebridge/MainActivity.java
find classes -name '*.class' -print0 | xargs -0 "$TOOLS/d8" --lib "$PLATFORM" --min-api 21 --output dex
cp dex/classes.dex classes.dex

"$TOOLS/aapt" package -f -M AndroidManifest.xml -S res -I "$RESOURCE_APK" -F unsigned.apk
"$TOOLS/aapt" add unsigned.apk classes.dex
"$TOOLS/zipalign" -f 4 unsigned.apk aligned.apk
"$TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out MiTVHomeBridge.apk aligned.apk

"$TOOLS/apksigner" verify --verbose --print-certs MiTVHomeBridge.apk
sha256sum MiTVHomeBridge.apk
