# Building Nomad

## Toolchain

The supported build environment is Linux with Bash, JDK 17, Python 3, `zip`, and the
Android SDK command-line tools. For example, install these SDK components:

```sh
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your SDK. `ANDROID_SDK_ROOT` is also
accepted; the fallback SDK path is `~/Android/Sdk`. `BUILD_TOOLS` and `ANDROID_JAR`
can override individual locations. If using mise, activate a Java version first.

## Debug APK

```sh
./build.sh --debug
./build.sh --debug install  # optional; needs a connected adb device
```

The output is `build/nomad-launcher.apk`. A development key is created under
`${XDG_CACHE_HOME:-$HOME/.cache}/nomad/debug.jks`. Debug APKs are debuggable and
allow WebView inspection. They install as **Nomad Dev** (`com.rawal.pocketdeck.debug`)
alongside the official app, with separate data. They do not update the release app.

`./build.sh` uses local release credentials when configured, otherwise a debug key.
Use the explicit flags in scripts so intent is clear. CI always uses `--debug`.

## Release signing

Create your own key for your own distribution:

```sh
mkdir -p keystore
keytool -genkeypair -keystore keystore/pocketdeck.jks -alias pocketdeck \
  -keyalg RSA -keysize 3072 -validity 10000
cp .signing.env.example .signing.env
chmod 600 .signing.env
```

Set `KEYSTORE`, `KEY_ALIAS`, and `KEY_PASS` in `.signing.env`. Use the same password
for the key and keystore. The file is sourced by Bash; keep it local and trusted.
The values may also be supplied through environment variables when no local file
is present. `./build.sh --release` fails if credentials are missing.

```sh
./build.sh --release
```

The script passes the signing password to `apksigner` through the environment and
verifies the resulting APK. It never prints the password. Back up the release key
securely: Android updates require the same signing identity. Official distribution
keys are not available to contributors or public CI.

## Frontend checks

```sh
npm ci
npx playwright install --with-deps chromium
npm test
```

Tests require Node.js 22+ and Python 3. The browser tests serve `app/assets` locally
and stub native bridges; native integration still needs a real device.

## Build steps

`aapt2` compiles Android resources and packages bundled assets. `javac` compiles the
native Java shell, `d8` produces DEX, `zipalign` aligns the archive, and `apksigner`
signs it. No Gradle wrapper or dependency download is needed for the Android build.
