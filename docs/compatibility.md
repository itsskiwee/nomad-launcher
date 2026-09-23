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

Each system lists the emulators that can play it. Nomad uses the first one that is
installed unless you choose another for the whole system (Settings → Library →
Emulators) or for one game (its ⋯ menu → Play with). Launch intents follow the ones
ES-DE ships for Android.

| Folder names | System | Emulators, in automatic order |
| --- | --- | --- |
| psp (and unlabelled folders) | PSP | PPSSPP / Gold, RetroArch (ppsspp) |
| ps1, psx | PlayStation | DuckStation, RetroArch (swanstation, pcsx_rearmed) |
| ps2 | PlayStation 2 | NetherSX2 / AetherSX2, ARMSX2 |
| n64 | Nintendo 64 | M64Plus FZ, Mupen64Plus AE, RetroArch (mupen64plus_next_gles3) |
| gc, gamecube | GameCube | Dolphin, Dolphin MMJR |
| wii | Wii | Dolphin, Dolphin MMJR |
| 3ds, n3ds | Nintendo 3DS | Azahar / Lime3DS, AzaharPlus, Citra |
| nds, ds | Nintendo DS | melonDS, DraStic, RetroArch (melondsds) |
| switch | Switch | Eden, Kenji-NX |
| vita, psvita | PlayStation Vita | Vita3K (`.psvita` files holding a title id) |
| snes, sfc | Super Nintendo | RetroArch (snes9x), Snes9x EX+ |
| nes, famicom | NES | RetroArch (fceumm) |
| gba | Game Boy Advance | RetroArch (mgba), My Boy! |
| gb, gbc | Game Boy / Color | RetroArch (gambatte) |
| genesis, megadrive, md | Genesis | RetroArch (genesis_plus_gx) |
| 32x, sega32x | 32X | RetroArch (picodrive) |
| saturn | Saturn | Yaba Sanshiro 2 |
| dreamcast, dc | Dreamcast | Flycast, Redream, RetroArch (flycast) |
| arcade, fbneo, mame | Arcade | RetroArch (fbneo) |
| pce, pcengine, tg16 | PC Engine | RetroArch (mednafen_pce_fast) |
| windows, winlator | Windows | Winlator Cmod, Winlator (`.desktop` shortcuts) |

RetroArch choices require the named core to be installed. Folder aliases, accepted
extensions and launch styles are defined in `Systems.java`. Launches were verified
on the primary device with PPSSPP, DuckStation, NetherSX2, M64Plus FZ and RetroArch;
the other intents come from ES-DE's definitions and need community confirmation.
Games, BIOS files and cores must be supplied and configured separately.

## Library tidying

Discs listed in an `.m3u` and the tracks of a `.cue`/`.gdi` are hidden behind their
playlist or cue sheet. A disc set without a playlist shows once and plays disc 1.
Versions of the same game in one system fold into one entry (a chosen version, else
USA, World, Europe, then others). BIOS files and folders such as `bios`, `saves`,
`states`, `media` and `downloaded_media` are skipped. Up to 3,000 games are scanned.

## Controllers

Gamepad buttons, D-pads, hat switches and the left stick navigate the interface:
A plays or presses, B goes back, X/Start opens a game's menu, Y favorites, L1/R1
switch pages (sections in Settings), Select opens Settings. Keyboards work with the
arrow keys, Enter and Escape. Tested with Android's injected gamepad events; physical
controller reports are welcome.

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
- PS2 widescreen patches: for discs with a patch in NetherSX2's own widescreen
  database, launching sets NetherSX2's `EmuCore/EnableWideScreenPatches` preference
  to the game's choice. The file is rewritten in place (owner and SELinux label kept),
  through Magisk's global mount namespace (`su -t 1`) because Android hides other
  apps' data from Nomad's own.
- Save sync can read and write emulator save folders under `Android/data` (see
  Settings → Saves → Find emulator saves). Files are written through `/storage` so
  the emulator keeps owning them.

The Magisk module under `device/` is a separate experimental device customization.
It is not included in or installed by the normal APK installation flow.

## Known limits

Text scaling, cloud document providers and more emulator/package variants need
additional work. RetroAchievements matching covers cartridge systems, arcade and PSP
ISOs; PS1/PS2/NDS/GameCube hashing is not implemented. PS2 widescreen detection reads
plain ISOs only (not CHD/CSO/GZ). Second-screen support was tested with Android's
simulated overlay display, not yet on dual-screen hardware. Root performance profiles are not portable
to other chips by changing a device name. See [the roadmap](../ROADMAP.md).
