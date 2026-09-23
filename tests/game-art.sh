#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
android_jar="${ANDROID_JAR:-$sdk/platforms/android-35/android.jar}"
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -cp "$android_jar" -d "$classes" app/src/com/rawal/pocketdeck/GameArt.java tests/GameArtTest.java app/src/com/rawal/pocketdeck/CoverArt.java tests/CoverArtTest.java app/src/com/rawal/pocketdeck/SaveSync.java app/src/com/rawal/pocketdeck/Root.java tests/SaveSyncTest.java app/src/com/rawal/pocketdeck/RetroAchievements.java tests/RetroAchievementsTest.java app/src/com/rawal/pocketdeck/Ps2Patches.java app/src/com/rawal/pocketdeck/Systems.java tests/Ps2PatchesTest.java
java -cp "$classes:$android_jar" com.rawal.pocketdeck.GameArtTest
java -cp "$classes:$android_jar" com.rawal.pocketdeck.CoverArtTest
java -cp "$classes:$android_jar" com.rawal.pocketdeck.SaveSyncTest
java -cp "$classes:$android_jar" com.rawal.pocketdeck.RetroAchievementsTest
java -cp "$classes:$android_jar" com.rawal.pocketdeck.Ps2PatchesTest
