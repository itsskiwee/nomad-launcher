# Changelog

## Unreleased

- Controller navigation everywhere: D-pad, hat switches, left stick and face/shoulder
  buttons; the first press after a touch or launch is never lost.
- Emulator catalogue with automatic setup: 21 systems, per-system and per-game emulator
  choice, and new systems GameCube, Wii, 3DS, Vita, Switch, Saturn and Windows (Winlator).
  ES-DE folder names are recognised.
- Automatic box art from libretro-thumbnails, matched by No-Intro/Redump name or title.
- Tidy library: playlists, cue sheets and disc sets fold into one entry, regional versions
  group with a version chooser, BIOS/data folders are skipped, games can be hidden, and
  scans no longer re-parse the library for every file (limit raised to 3,000 games).
- Import covers, videos, titles, favorites and hidden flags from ES-DE or Skraper media.
- Game preview clips on Home.
- Save sync between devices through a shared folder, with root discovery of emulator saves.
- RetroAchievements progress in each game's menu.
- PS2 widescreen patches through NetherSX2's own database (root).
- Second-screen presentation for dual-screen handhelds.

## 0.3.1.1 — 2026-09-22

- Show measured net battery power in watts, with charging/discharging direction.
- Estimate time to full using Android’s charge estimate, observed charge gain, or battery-gauge average current.
- Estimate discharge time from the battery gauge while percentage observations are warming up.
- Distinguish plugged-in, charging, draining while plugged in, idle, and full states.
- Show unavailable readings honestly on devices without supported battery sensors.

## 0.3.1 — 2026-09-22

- Add per-game total playtime and last-session duration with optional Android usage access.
- Add a Playtime settings page and game-menu statistics.
- Add battery charge loss, average discharge rate, and estimated remaining time.
- Open battery details by tapping the status-bar battery indicator.
- Add foreground-session accounting and tracking UI tests.

## 0.3.0 — 2026-09-21

First packaged public release of Nomad Launcher.

### Added

- MIT license, contributor guides, issue forms, security policy, and project roadmap.
- Installable signed APK releases with SHA-256 checksums and signing information.
- Portable debug builds that install separately as Nomad Dev without release credentials.
- CI for JavaScript, simulated device checks, browser interactions, and APK builds.
- Explicit opt-in for root features on new installations.

### Improved

- Home and Favorites pages with vertical snapping and reduced Favorites spacing.
- Explicit game menus instead of long-press menus during scrolling.
- Custom Nomad adaptive icon with monochrome themed-icon support.
- Startup reveal timeout, WebView recovery, and graceful fallback when video color sampling is blocked.
- Music access opens Android's permission screen.
- First launch no longer changes system screen timeout or system/lock wallpaper.
- Version display follows the APK manifest.

### Compatibility

- Minimum Android 8.0 (API 26); primary device testing on Android 13 / Redmi Note 8 Pro.
- Existing dedicated-device installations retain enabled root features on upgrade.
- Package ID remains `com.rawal.pocketdeck` to preserve update compatibility.
- Performance profiles remain limited to validated `begonia` hardware.
