# Galaxy Tab S6 Lite verification

November 21, 2026 · Samsung SM-P615 (`gta4xl`) · LineageOS, Android 16

## Device checks

| Check | Result |
| --- | --- |
| Windows build and ADB install | Built with JDK 17 and Android SDK 36. Verified APK signing and installed over USB. The manifest declares no app permissions. |
| Dashboard and Home recovery | Opened the app in portrait and landscape, navigated its tabs and game cards with injected touch events, and verified that Android’s Home settings lists Nomad Launcher alongside Trebuchet. Returned the tablet to Trebuchet after testing. |
| Controller events | Injected Android D-pad and gamepad A/B events over ADB. Verified focus movement, confirmation, and back navigation. A physical Bluetooth controller was unavailable. |
| Folder access and library | Chose a test folder in Android’s picker. Indexed four synthetic game files with no ROM data, ignored a text file, displayed local artwork, filtered by system and title, and checked favorite and recent entries across a process restart. Checked a manual rescan and removed the test folder from the app. |
| Launch permissions | Verified the no-handler message and per-system app selection. A temporary Android test receiver read the selected file via `ACTION_VIEW` and was denied access to a sibling. The test used a receiver because no compatible emulator was available. Real game boot and PPSSPP were outside this device check. |

## Idle memory

For the empty-library measurement, force-stopped Nomad, opened stock Home, and waited eight seconds. Collected five readings about two seconds apart. Opened Nomad, waited eight seconds again, and collected the same number of readings. Measured system availability with `adb shell cat /proc/meminfo` and Nomad’s proportional set size (PSS) with `adb shell dumpsys meminfo com.rawal.nomadlauncher`. Airplane mode was on with Wi-Fi available.

| Median across five samples | Stock Home, Nomad stopped | Nomad foreground |
| --- | ---: | ---: |
| System available RAM | 1,300,112 KiB | 1,283,124 KiB |
| Nomad process PSS | — | 30,907 KiB (30.2 MiB) |

System `MemAvailable` varies as Android reclaims memory and other processes run. Nomad’s process PSS is the direct app measurement. A separate reading after navigation with four indexed test files and one local image was 58.3 MiB PSS.
