# Architecture

```text
Android activity
  ├─ WebView: bundled HTML / CSS / JavaScript
  │    ├─ Deck bridge: library, launching, artwork, settings
  │    ├─ Theme bridge: video assets and wallpaper colors
  │    └─ Performance bridge: optional root controls
  ├─ Storage Access Framework: user-selected game folders
  ├─ MediaHub: Android media sessions
  └─ Local preferences and private artwork storage
```

`MainActivity.java` owns the window, local WebView content origin, JavaScript
bridges, scans, launcher integration, and game launch intents. Only known bundled
asset paths and private artwork/video paths are served from the asset origin.
External navigation is blocked. API inputs and path handling remain security-
sensitive because JavaScript bridges can invoke Android operations.

`app.js` renders Home, Favorites, Library, Apps, and Settings from native snapshots.
Native state arrives through `receiveState`; media, theme, and performance state
have separate callbacks. The startup reveal waits for initial data/assets with a
six-second fallback. The native activity can recreate a lost WebView renderer and
reload an unresponsive page.

`Systems.java` maps platform folder names to emulator intents. The Storage Access
Framework grants access to selected trees. Games remain in their original folders;
artwork and video themes are copied to private app storage. Preferences store
favorites, play counts, folder grants, and UI selections.

`PerformanceBridge` uses a dedicated worker and a bounded root session. The shipped
controller validates supported hardware and checks readback/rollback. Root features
are opt-in for new installs. `IdleCleanup` has separate foreground/playback guards.

The Android package name is intentionally retained from the earlier PocketDeck
prototype. Renaming it would create a different app and break normal update/data
continuity. User-visible branding is Nomad.
