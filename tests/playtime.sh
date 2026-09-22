#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" app/src/com/rawal/pocketdeck/PlayTimeline.java tests/PlayTimelineTest.java
java -cp "$classes" com.rawal.pocketdeck.PlayTimelineTest
