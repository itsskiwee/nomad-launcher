#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version="${1:?Usage: ./scripts/release.sh VERSION}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || { echo 'Expected version X.Y.Z or X.Y.Z.N' >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo 'Commit or stash working changes first.' >&2; exit 1; }
[[ "$(git branch --show-current)" == main ]] || { echo 'Release from main.' >&2; exit 1; }
git fetch origin main
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || { echo 'Push main before releasing.' >&2; exit 1; }
python3 - "$version" <<'PY'
import sys, xml.etree.ElementTree as ET
actual = ET.parse('app/AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
if actual != sys.argv[1]: raise SystemExit('Manifest version does not match requested release.')
PY
notes="docs/releases/v$version.md"
[[ -f "$notes" ]] || { echo "Missing $notes" >&2; exit 1; }
if git rev-parse "refs/tags/v$version" >/dev/null 2>&1; then echo 'Tag already exists; do not replace releases.' >&2; exit 1; fi
./build.sh --release
folder="dist/v$version"
mkdir -p "$folder"
cp build/nomad-launcher.apk "$folder/nomad-launcher.apk"
(cd "$folder" && sha256sum nomad-launcher.apk > SHA256SUMS)
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
"${BUILD_TOOLS:-$sdk/build-tools/35.0.0}/apksigner" verify --print-certs "$folder/nomad-launcher.apk" > "$folder/SIGNING-CERTIFICATE.txt"
git tag -a "v$version" -m "Nomad Launcher $version"
git push origin "v$version"
gh release create "v$version" "$folder/nomad-launcher.apk" "$folder/SHA256SUMS" \
  "$folder/SIGNING-CERTIFICATE.txt" --verify-tag --draft \
  --title "Nomad Launcher $version" --notes-file "$notes"
