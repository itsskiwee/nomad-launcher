# Nomad Launcher

Nomad is an offline Android game library and optional Home app. Browse games from folders you choose, pin favorites, search by title or system, and hand files off to installed emulators. Touch, D-pad navigation, and the Android gamepad confirm/back buttons use the same controls.

## Build and install on Windows

Install JDK 17 and the Android SDK command-line tools. Set `JAVA_HOME` and `ANDROID_HOME`, or put the JDK on `PATH` and pass the SDK path with `-Sdk`. Run the following command from the SDK command-line tools directory. [Android SDK command-line tools](https://developer.android.com/studio#command-tools)

```powershell
.\sdkmanager.bat "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

Run the build from this project directory.

```powershell
.\build.ps1
.\build.ps1 -Install
.\build.ps1 -Install -Serial YOUR_DEVICE_SERIAL
```

The first command produces `build/nomad-launcher.apk`. The other commands also install it through authorized USB or wireless debugging. To install an existing APK, run `adb install -r .\build\nomad-launcher.apk`. Open **Nomad Launcher** from the app drawer. The application targets Android 16 and requires Android 9 or later.

The Windows build uses the JDK and SDK directly. It signs the sideload APK with `%USERPROFILE%\.android\debug.keystore`, creating that key if needed. Keep the key if you want later builds to upgrade in place. Use a dedicated release key for wider distribution.

## Connect and play

1. Choose **Connect folder** and grant read access in Android’s folder picker. Connect additional folders from **Settings**. Nomad stores a local index and leaves the original files in place.
2. On **Home**, switch between **All games**, **Favorites**, and **Recent**. Tap a cover or move focus onto it to preview the game. Use **Play** or confirm on the focused cover to launch. The star toggles favorites and the options menu lets you choose an emulator. Use **Library** to search titles and filter by system.
3. Use **Settings → Manage folders** to rescan or disconnect a folder. **Clear artwork cache** discards the in-memory thumbnails. Add a PNG, JPEG, or WebP with the same basename next to a game or inside a neighboring `covers` or `artwork` directory. For example, `Game.png` supplies a tile cover and `Game.backdrop.jpg` supplies a widescreen backdrop for `Game.gba`. Rescan after adding images.

The scanner recognizes NES, Super Nintendo, Game Boy and Game Boy Color, Game Boy Advance, Mega Drive / Genesis, Master System, Game Gear, Nintendo 64, and Nintendo DS by file extension. PSP `.cso` and Dreamcast `.cdi` are also recognized. Put ambiguous disc files in an identifying folder such as `PS1`, `PSP`, or `Dreamcast`. For example, use `PS1/Game.chd` or `PSP/Game.iso`. Dreamcast is experimental. ZIP archives, CUE/BIN disc sets, and multi-disc playlists are outside this milestone.

Nomad discovers installed Android apps that advertise a compatible content URI handler and implements PPSSPP’s Android handoff. A handler still needs to support the game format and may need its own BIOS or emulator settings. A recent entry records an accepted Android launch request. Any resume or save-state behavior depends on the emulator. [PPSSPP command-line reference](https://www.ppsspp.org/docs/reference/command-line/#android)

Scans start on folder connection or when requested. Each folder is bounded to 12,000 directory entries and 16 nested levels. A failed rescan keeps the last successful index for that folder. The app requests no Internet, location, cellular, or broad storage access and has no background service. It contains no emulator cores, ROMs, BIOS files, or downloaded covers.

## Restore Android Home or roll back

- To use Nomad as Home, open **Settings → Default Home app** inside Nomad and select **Nomad Launcher**. To restore the previous launcher, use the same screen and select it. On the tested LineageOS device, the stock launcher is **Trebuchet** (`com.android.launcher3`). You can also open Android **Settings → Apps → Default apps → Home app** from the system settings.
- To remove Nomad, restore the previous Home app, then uninstall Nomad through Android settings or run `adb uninstall com.rawal.nomadlauncher`. Uninstalling clears Nomad’s index, favorites, recent entries, and folder grants. Games and emulator data stay in place.
- To roll back after an incompatible update or signing-key change, uninstall Nomad and sideload the previous APK. Reconnect your game folders afterward.

See [tablet verification](VERIFICATION.md) for measured idle memory, checks performed, and limitations.
