# Verification

## Console Home · 0.3.0

Verified an in-place upgrade from 0.2.0 in the Android 9 emulator. The folder grant, games, favorite, and recent launch survived the database migration. Verified touch selection, D-pad movement from the shelf to a tile and Play, and injected A/B actions. Inspected landscape, tablet portrait, and narrow portrait. Confirmed that a separate local cover supplies a tile and that a widescreen backdrop takes over the Home screen. Selecting a game without artwork restores the bundled coast scene. The Galaxy Tab was disconnected during this pass.

<details>
<summary>Backdrop artwork provenance</summary>

The built-in image generation tool produced the [bundled coastal backdrop](app/res/drawable-nodpi/coast.webp). The lunar scene in the emulator screenshot is a local test image for the fictional game Moon Relay. The lunar scene and test files are excluded from the APK.

**Coastal backdrop prompt**

> Use case: photorealistic-natural. Create an original widescreen 16:9 background image for the native home screen of an independent gaming console. Image only, no UI, symbols, letters, brands, typography, logos, or watermark. A desolate coastal road on a North Atlantic volcanic island at blue hour, viewed from a low viewpoint. Far right, an immense dark striated basalt cliff catches a narrow band of pale moonlight above a fog-filled inlet. A very small unlit road bends toward the cliffs at the far right. Left sixty percent is spacious near-black indigo ocean and heavy twilight haze with extremely little high frequency detail, designed for white interface text overlay. Upper quarter is calm dark sky. Slate-blue monochromatic palette, natural soft fog, gentle analog grain, real photographic texture, subdued highlights, quiet and premium. Wide film still, avoid neon, purple, sci-fi props, people, pillars and artificial lens flare.

**Local test artwork prompt**

> Use case: stylized-concept. One original cinematic widescreen 16:9 key-art landscape for a fictional test game about repairing a communications relay on the Moon. Image only, no lettering, game logo, UI, watermark, familiar franchise imagery or recognizable hardware. On the right half a solitary small suited repair technician pauses at the foot of a huge old parabolic radio dish on a rough moonscape, with a tiny distant crescent Earth near the right horizon. Left half is mostly dark craggy charcoal lunar plain and negative space; upper third calm starless almost black sky. A beam of cold sunlight grazes the ground and the dish, with one muted amber work light. Fine analog grain, muted slate shadows, silver highlights and pale lunar dust. Realistic film production concept art with quiet scale and controlled highlights. A distinctive composition intelligible when cropped to a square.

</details>

## Dashboard redesign · 0.2.0

Built and installed the signed APK in an Android 9 emulator. Checked the empty dashboard, folder selection, local cover rendering, touch previews, favorites, recents, and library search using six synthetic filenames and one local PNG. Verified the landscape layout, tablet portrait, and narrow portrait. Injected D-pad events to verify that focus moves from the top bar to the play action, shelf controls, and game tiles. Injected gamepad A/B to confirm an action and dismiss its dialog. A temporary receiver verified read access to the selected file and denied access to its sibling. The physical tablet was unavailable for this pass.

## Galaxy Tab S6 Lite · 0.1.0

November 21, 2026 · Nomad Launcher 0.1.0 · Samsung SM-P615 (`gta4xl`) · LineageOS, Android 16

### Device checks

| Check | Result |
| --- | --- |
| Windows build and ADB install | Built with JDK 17 and Android SDK 36. Verified APK signing and installed over USB. The manifest declares no app permissions. |
| Dashboard and Home recovery | Opened the app in portrait and landscape, navigated its tabs and game cards with injected touch events, and verified that Android’s Home settings lists Nomad Launcher alongside Trebuchet. Returned the tablet to Trebuchet after testing. |
| Controller events | Injected Android D-pad and gamepad A/B events over ADB. Verified focus movement, confirmation, and back navigation. A physical Bluetooth controller was unavailable. |
| Folder access and library | Chose a test folder in Android’s picker. Indexed four synthetic game files with no ROM data, ignored a text file, displayed local artwork, filtered by system and title, and checked favorite and recent entries across a process restart. Checked a manual rescan and removed the test folder from the app. |
| Launch permissions | Verified the no-handler message and per-system app selection. A temporary Android test receiver read the selected file via `ACTION_VIEW` and was denied access to a sibling. The test used a receiver because no compatible emulator was available. Real game boot and PPSSPP were outside this device check. |

### Idle memory

For the empty-library measurement, force-stopped Nomad, opened stock Home, and waited eight seconds. Collected five readings about two seconds apart. Opened Nomad, waited eight seconds again, and collected the same number of readings. Measured system availability with `adb shell cat /proc/meminfo` and Nomad’s proportional set size (PSS) with `adb shell dumpsys meminfo com.rawal.nomadlauncher`. Airplane mode was on with Wi-Fi available.

| Median across five samples | Stock Home, Nomad stopped | Nomad foreground |
| --- | ---: | ---: |
| System available RAM | 1,300,112 KiB | 1,283,124 KiB |
| Nomad process PSS | — | 30,907 KiB (30.2 MiB) |

System `MemAvailable` varies as Android reclaims memory and other processes run. Nomad’s process PSS is the direct app measurement. A separate reading after navigation with four indexed test files and one local image was 58.3 MiB PSS.
