#!/usr/bin/env bash
# Builds Pocket Deck from source: app/AndroidManifest.xml + app/res (aapt2),
# app/src (javac + d8) and app/assets, then aligns and signs the APK.
#
#   ./build.sh            -> build/pocketdeck.apk
#   ./build.sh install    -> build + adb install -r
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-17.0.20+101}"
BUILD_TOOLS="${BUILD_TOOLS:-$HOME/Android/Sdk/build-tools/35.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$HOME/Android/Sdk/platforms/android-35/android.jar}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$BUILD_TOOLS:$PATH"

# Local signing credentials are excluded from version control.
if [[ -f .signing.env ]]; then source .signing.env; fi
KEYSTORE="${KEYSTORE:-keystore/pocketdeck.jks}"
KEY_ALIAS="${KEY_ALIAS:-pocketdeck}"
: "${KEY_PASS:?Set KEY_PASS or configure .signing.env (see README.md)}"
export KEY_PASS

rm -rf build && mkdir -p build/res build/classes build/dex

# Resources + manifest -> base APK (no code yet).
aapt2 compile --dir app/res -o build/res/res.zip
aapt2 link -o build/base.apk -I "$ANDROID_JAR" --manifest app/AndroidManifest.xml \
  -A app/assets build/res/res.zip

# Java -> dex.
javac -source 8 -target 8 -Xlint:-options -nowarn -classpath "$ANDROID_JAR" \
  -d build/classes app/src/com/rawal/pocketdeck/*.java
d8 --release --lib "$ANDROID_JAR" --min-api 26 --output build/dex $(find build/classes -name '*.class')
cp build/dex/classes.dex build/classes.dex
(cd build && zip -q base.apk classes.dex)

zipalign -p -f 4 build/base.apk build/aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEY_PASS --key-pass env:KEY_PASS \
  --out build/pocketdeck.apk build/aligned.apk
apksigner verify build/pocketdeck.apk
rm -f build/base.apk build/aligned.apk build/classes.dex
echo "built build/pocketdeck.apk"

if [[ "${1:-}" == "install" ]]; then
  adb install --no-incremental -r build/pocketdeck.apk
fi
