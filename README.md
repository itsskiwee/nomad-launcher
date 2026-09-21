# Nomad Launcher

A landscape Android game launcher with cover art, a swipeable Favorites page,
video backgrounds, and music controls. Built with a Java Android shell and a local
HTML/CSS/JavaScript interface, without Gradle.

![Nomad icon](art/brand/nomad-icon.png)

## Features

- **Home:** most-played games first. Tap a cover to select it, then tap again to play.
- **Favorites:** swipe up from Home for favorite games; swipe down to return.
- **Library:** search, filter favorites, and launch installed Android games or ROMs.
  Explicit options buttons manage favorites, artwork, and game deletion.
- **Emulators:** folder names identify platforms. Integrations include PPSSPP,
  DuckStation, NetherSX2, M64Plus FZ, and RetroArch.
- **Appearance:** custom covers, wallpaper, video backgrounds, and adaptive colors.
- **Music:** controls for the active media session through notification access.
- **Device controls:** root-assisted performance profiles and power off.

Games, emulators, imported covers, and video themes are not included.
The internal Android package remains `com.rawal.pocketdeck` for compatibility
with existing installations.

## Build

Requires JDK 17, Android SDK platform 35, and Android build-tools 35.0.0.
The script defaults to a local mise JDK installation and `~/Android/Sdk`;
set `JAVA_HOME`, `BUILD_TOOLS`, and `ANDROID_JAR` for another installation.

Create your own signing key (the command prompts for a password):

```sh
mkdir -p keystore
keytool -genkeypair -keystore keystore/pocketdeck.jks -alias pocketdeck \
  -keyalg RSA -keysize 3072 -validity 10000
cp .signing.env.example .signing.env
```

Set `KEY_PASS` in `.signing.env` to your keystore password. Use the same password
for the key and keystore. Signing keys and the local configuration are ignored
by Git and are never included in this repository. No API key is required.

```sh
./build.sh          # Produces build/pocketdeck.apk
./build.sh install  # Builds and updates the connected device with adb install -r
```

Updating an existing installation requires its original signing key. Use update
installation to retain settings and Android folder access grants.

## Project layout

```text
app/AndroidManifest.xml       Android entry points and permissions
app/src/com/rawal/pocketdeck/ Native shell, game discovery, artwork, media, root controls
app/assets/                  Local WebView interface and device control scripts
app/res/                     Android styles and adaptive launcher icon
art/brand/                   Nomad logo source and preview
tests/                       Local checks and explicit device integration tests
device/pocketdeck-blackboot/ Optional Magisk boot animation module
build.sh                     SDK build and signing script
```

The launcher serves its bundled interface and local media through a WebView asset
origin. JavaScript bridges connect it to Android game discovery, launching, folder
selection, artwork storage, media sessions, and device settings.

## Root and device behavior

This project was developed for a rooted Xiaomi Redmi Note 8 Pro (`begonia`).
Performance profiles validate the supported CPU/GPU tables before making changes;
other hardware is not supported by those controls. Profiles include Balanced,
Power saver, Performance, and Turbo. Changes use supported frequencies, preserve
thermal policies, and verify readback. Balanced restores the saved baseline.

Power off immediately runs Android's root shutdown command. The launcher also
keeps the display awake, configures PPSSPP's exit behavior, and can grant its music
notification access through root. While the launcher is foreground, idle cleanup
can stop inactive Spotify and YouTube processes; active playback and visible
windows prevent cleanup. Review the device scripts before using them on another
phone.

The optional Magisk module replaces the boot animation and contains device
rotation properties. It is separate from the APK build and is not installed by
`build.sh`.

## Checks

```sh
node --check app/assets/app.js
node tests/boot-reveal.cjs
python3 tests/idle-cleanup.py
```

`tests/device-controls.py` is an explicit integration test for supported rooted
hardware. It changes performance profiles and restores Balanced on completion.
It is not part of the ordinary build.

## Local files

Generated APKs, signing credentials, device backups, decompiled recovery files,
and imported game artwork/video themes remain local and are excluded from Git.
