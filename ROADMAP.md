# Roadmap

This is a working list, not a delivery schedule. Proposals and compatibility
reports are welcome through GitHub issues.

## Next priorities

- Broader physical-device testing, especially Android 8–12 and Android 14+.
- More resilient emulator launching across package variants and storage providers.
- Controller and D-pad navigation throughout the interface.
- Large-library performance and multi-row Favorites behavior.
- Accessibility review: focus order, screen readers, text scaling, and contrast.
- Better onboarding for game folders, platform detection, and emulator setup.

## Later exploration

- User-configurable emulator choices per platform.
- Portable library/settings export without copying game files.
- Translation support.
- Theme packaging with clear licensing and attribution.

## Project boundaries

Nomad launches games through installed emulators; it is not an emulator or a ROM
download service. Root tuning should remain optional and hardware-specific. Core
library browsing should remain usable without an account, network service, or root.
