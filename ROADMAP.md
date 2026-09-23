# Roadmap

This is a working list, not a delivery schedule. Proposals and compatibility
reports are welcome through GitHub issues.

## Next priorities

- Broader physical-device testing, especially Android 8–12 and Android 14+.
- Physical confirmation of the Dolphin, Azahar, melonDS, Vita3K, Eden and Winlator intents.
- Controller testing on dedicated handhelds (Retroid, AYN, Anbernic) and dual-screen devices.
- Large-library performance (thousands of games) and multi-row Favorites behavior.
- RetroAchievements hashing for disc systems (PS1, PS2, GameCube) and NDS.
- Accessibility review: focus order, screen readers, text scaling, and contrast.
- Better onboarding for game folders, platform detection, and emulator setup.

## Later exploration

- Portable library/settings export without copying game files.
- Daijisho import (it keeps its library in a private database, unlike ES-DE).
- Translation support.
- Theme packaging with clear licensing and attribution.

## Project boundaries

Nomad launches games through installed emulators; it is not an emulator or a ROM
download service. Root tuning should remain optional and hardware-specific. Core
library browsing should remain usable without an account, network service, or root.
