# Changelog

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
