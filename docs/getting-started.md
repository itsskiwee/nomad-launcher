# Getting started

## Install and update

Download `nomad-launcher.apk` from the [latest GitHub release](https://github.com/itsskiwee/nomad-launcher/releases/latest),
or add the repository to [Obtainium](https://obtainium.imranr.dev/) to get updates
automatically. Open the APK on Android 8.0 or newer. Android may ask you to allow your browser or file
manager to install apps. Nomad does not require an account, API key, or root for its
core launcher features.

Update by installing a newer official APK over the existing one. This preserves
settings and folder grants. Do not uninstall first unless you want a fresh setup.
Self-built debug APKs install separately as Nomad Dev and do not replace your
official installation. Self-built release APKs require the original signing key
to update an official installation.

To verify a download on a computer, put the APK and `SHA256SUMS` from the same
release in a directory and run `sha256sum -c SHA256SUMS`. The checksum verifies the
file against the release metadata; `SIGNING-CERTIFICATE.txt` records the Android
signer's certificate fingerprint for checking update continuity.

## Add games

1. Install and configure the emulator you want to use.
2. Put your games in folders named for their platform, such as `ROMs/PSP`, `ROMs/PS1`,
   `ROMs/N64`, or `ROMs/SNES`.
3. Open Nomad → Library → the folder-plus button. Grant access using Android's picker.
4. Wait for the scan, then tap a game to launch it.

Unrecognized folder names default to PSP. Installed Android apps identified as games
also appear in the library; emulator apps are kept in Apps. See the
[compatibility table](compatibility.md) for supported launch targets and limitations.

## Home and Favorites

Home shows your most-played games. Tap a cover to select it, then tap again to play.
Swipe up for Favorites and down to return. Use a game's **⋯** menu to add/remove
favorites or choose custom artwork. Library has search and a Favorites filter.

The game menu also offers deletion. For ROMs this deletes the actual game file
after a second confirming tap. For Android games it opens the system uninstaller.

## Appearance and music

Settings → Theme lets you choose colors, wallpaper/background modes, and local MP4
video backgrounds. Custom cover artwork is managed through game menus or Settings →
Artwork. Missing covers download from libretro-thumbnails without an account; your
own artwork always takes priority. No video-theme catalog is bundled.

Music controls require notification access. Use Settings → Music to open Android's
notification-access screen and enable Nomad. This enables access to active media
sessions. Return to Nomad after granting access.

## Optional device features

Settings → Device → Root features enables hardware profiles, power off, idle Spotify/
YouTube cleanup, and PPSSPP exit integration. Read [the compatibility notes](compatibility.md#root-features)
first. Root is not automatically requested at initial startup on a new installation.

Nomad keeps the screen awake while visible. It does not change the system-wide
screen timeout or wallpaper automatically. The explicit black-wallpaper action in
Appearance changes both system and lock-screen wallpaper.

## Troubleshooting

- **No games:** verify the folder grant and platform folder name, then refresh Library.
- **Game won't launch:** first open it directly in the emulator; check package/core
  compatibility and emulator storage permissions.
- **Blank screen:** allow several seconds for initial data/artwork loading. Update
  Android System WebView and relaunch. If it persists, report Android/WebView versions.
- **No music controls:** check notification access and that a media app has a session.
- **App not installed:** check Android version and available storage. A signing-key
  mismatch prevents installing a self-built APK over an official APK.

Report reproducible issues with [the bug form](https://github.com/itsskiwee/nomad-launcher/issues/new/choose).
