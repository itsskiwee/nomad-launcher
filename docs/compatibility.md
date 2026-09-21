# Compatibility

## Android

| Area | Current status |
| --- | --- |
| Minimum Android | Android 8.0 / API 26 |
| Target SDK | Android 15 / API 35 |
| Primary tested phone | Redmi Note 8 Pro (`begonia`), Android 13 |
| Other devices | Expected core support; physical-device verification needed |
| Orientation | Landscape |
| Runtime | Android System WebView with JavaScript enabled by the app |
| Root | Optional; disabled by default for new installs |
| Package ID | `com.rawal.pocketdeck` (preserved for upgrades) |

The APK has no bundled native `.so` libraries. Actual emulator CPU architecture
support is determined by the emulator you install. Minimum SDK support is not a
claim that every OS/WebView/emulator combination has been tested.

## Emulator integrations

| Folder | Launch target | Notes |
| --- | --- | --- |
| PSP | PPSSPP / PPSSPP Gold | ISO, CSO, CHD, PBP; emulator determines format support |
| PS1 / PSX | DuckStation | `com.github.stenzek.duckstation` |
| PS2 | NetherSX2 / compatible AetherSX2 package | `xyz.aethersx2.android` |
| N64 | M64Plus FZ | `org.mupen64plusae.v3.fzurita` |
| SNES | RetroArch / snes9x | Requires matching installed core |
| NES | RetroArch / fceumm | Requires matching installed core |
| GBA | RetroArch / mgba | Requires matching installed core |
| GB / GBC | RetroArch / gambatte | Requires matching installed core |
| Genesis / MD | RetroArch / genesis_plus_gx | Requires matching installed core |
| 32X | RetroArch / picodrive | Requires matching installed core |
| NDS | RetroArch / melondsds | Requires matching installed core |
| Dreamcast / DC | RetroArch / flycast | Requires matching installed core |
| Arcade / FBNeo | RetroArch / fbneo | Requires compatible ROM set |
| PCE | RetroArch / mednafen_pce_fast | Requires matching installed core |

RetroArch integration targets `com.retroarch.aarch64`. Folder aliases and accepted
extensions are defined in `Systems.java`. Emulator detection does not guarantee
that a game format is supported by the installed emulator version. Games, BIOS
files, and cores must be supplied and configured separately.

## Root features

New installations keep root features off. Enable them in Settings → Device only
on a device where you intend to grant Nomad superuser access. Existing dedicated-
device installs retain their previous root behavior when upgrading.

- Performance profiles validate `begonia` CPU/GPU tables and reject unknown hardware.
- Balanced restores the baseline captured for the current boot. Other root tools
  changing the same controls can invalidate the expected state.
- Restore Balanced before disabling root features; disabling prevents new root
  actions, but does not automatically undo previously applied clock settings.
- Power off invokes Android's root shutdown command immediately when tapped.
- Idle cleanup can force-stop inactive Spotify/YouTube while Nomad is visible.
  Active playback, visible windows, and missing diagnostic data skip cleanup.
- PPSSPP integration may write `PauseMenuExitsEmulator = True` to supported config
  locations so exiting a game returns to the launcher.

The Magisk module under `device/` is a separate experimental device customization.
It is not included in or installed by the normal APK installation flow.

## Known limits

Controller navigation, text scaling, large libraries, cloud document providers,
and more emulator/package variants need additional work. Non-PSP games do not have
automatic embedded artwork extraction. Root performance profiles are not portable
to other chips by changing a device name. See [the roadmap](../ROADMAP.md).
