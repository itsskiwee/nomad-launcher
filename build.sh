#!/usr/bin/env bash
# Build Nomad with the Android SDK tools, without Gradle.
set -euo pipefail
cd "$(dirname "$0")"

mode=auto
install=false
for arg in "$@"; do
  case "$arg" in
    --debug) mode=debug ;;
    --release) mode=release ;;
    install) install=true ;;
    *) echo "Usage: ./build.sh [--debug|--release] [install]" >&2; exit 2 ;;
  esac
done

if [[ -z "${JAVA_HOME:-}" ]]; then
  command -v javac >/dev/null || { echo 'JDK 17 is required. Set JAVA_HOME.' >&2; exit 1; }
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BUILD_TOOLS="${BUILD_TOOLS:-$sdk/build-tools/35.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$sdk/platforms/android-35/android.jar}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$BUILD_TOOLS:$PATH"
for tool in javac jar keytool aapt2 d8 zipalign apksigner zip; do
  command -v "$tool" >/dev/null || { echo "Missing $tool. See docs/building.md." >&2; exit 1; }
done
javac -version >/dev/null 2>&1 || { echo "JDK is not configured. Set JAVA_HOME to a working JDK 17 installation." >&2; exit 1; }
[[ -f "$ANDROID_JAR" ]] || { echo "Missing Android platform: $ANDROID_JAR" >&2; exit 1; }

if [[ "$mode" != debug && -f .signing.env ]]; then source .signing.env; fi
if [[ "$mode" == auto ]]; then
  if [[ -n "${KEY_PASS:-}" ]]; then mode=release; else mode=debug; fi
fi
if [[ "$mode" == release ]]; then
  KEYSTORE="${KEYSTORE:-keystore/pocketdeck.jks}"
  KEY_ALIAS="${KEY_ALIAS:-pocketdeck}"
  : "${KEY_PASS:?Release signing requires KEY_PASS or .signing.env}"
  [[ -f "$KEYSTORE" ]] || { echo "Signing keystore not found: $KEYSTORE" >&2; exit 1; }
else
  KEYSTORE="${XDG_CACHE_HOME:-$HOME/.cache}/nomad/debug.jks"
  KEY_ALIAS=androiddebugkey
  KEY_PASS=android
  if [[ ! -f "$KEYSTORE" ]]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
      -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
      -dname 'CN=Android Debug,O=Android,C=US' >/dev/null 2>&1
  fi
fi
export KEY_PASS

rm -rf build
mkdir -p build/res build/classes build/dex
cp -R app/assets build/assets
cp LICENSE build/assets/LICENSE.txt
cp THIRD_PARTY_NOTICES.md build/assets/THIRD_PARTY_NOTICES.txt
cp app/AndroidManifest.xml build/AndroidManifest.xml
if [[ "$mode" == debug ]]; then
  sed -i \
    -e 's/<application/<application android:debuggable="true"/' \
    -e 's/package="com.rawal.pocketdeck"/package="com.rawal.pocketdeck.debug"/' \
    -e 's/android:label="Nomad"/android:label="Nomad Dev"/' \
    -e 's/android:name=".MainActivity"/android:name="com.rawal.pocketdeck.MainActivity"/' \
    -e 's/android:name=".MediaListener"/android:name="com.rawal.pocketdeck.MediaListener"/' \
    build/AndroidManifest.xml
fi
aapt2 compile --dir app/res -o build/res/res.zip
aapt2 link -o build/base.apk -I "$ANDROID_JAR" --manifest build/AndroidManifest.xml \
  -A build/assets build/res/res.zip
javac -source 8 -target 8 -Xlint:-options -nowarn -classpath "$ANDROID_JAR" \
  -d build/classes app/src/com/rawal/pocketdeck/*.java
mapfile -d '' classes < <(find build/classes -name '*.class' -print0)
d8 --release --lib "$ANDROID_JAR" --min-api 26 --output build/dex "${classes[@]}"
cp build/dex/classes.dex build/classes.dex
(cd build && zip -q base.apk classes.dex)
zipalign -p -f 4 build/base.apk build/aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEY_PASS --key-pass env:KEY_PASS \
  --out build/nomad-launcher.apk build/aligned.apk
apksigner verify build/nomad-launcher.apk
rm -f build/base.apk build/aligned.apk build/classes.dex
printf 'Built %s APK: build/nomad-launcher.apk\n' "$mode"
if "$install"; then adb install --no-incremental -r build/nomad-launcher.apk; fi
