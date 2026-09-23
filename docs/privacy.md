# Privacy and permissions

Nomad has no account system, analytics SDK, advertising SDK, or crash-upload service.
Its interface and game library are stored locally. There is no Nomad backend.

## Local data

Selected game folders, favorites, play counts, custom artwork, themes, and UI settings
are stored on the device. ROMs remain in the folders you select. Removing a folder
from the library does not copy its contents; the explicit game-delete action deletes
the selected game file. Uninstalling Nomad removes its private data and grants.

## Permissions

- **Network state:** displays connectivity status.
- **Internet:** album artwork from a URL supplied by an active media app; optional
  cover art, RetroAchievements and emulator download links described below.
- **Set wallpaper:** supports the explicit black-wallpaper action.
- **Wake lock / screen flag:** keeps the display awake while the launcher is visible.
- **Folder access:** Android's system picker grants access to folders you select.
- **Notification access:** optional; enables active media-session controls. The listener
  does not implement notification-content collection or upload.
- **Root:** optional advanced features can change performance controls, stop idle media
  apps, change PPSSPP configuration, and power off the device.

Media artwork requests go to the host selected by the media app; that host may see
your IP address.

## Network features

- **Cover art** (on by default, Settings → Artwork): for games without a cover, Nomad
  downloads the per-system file list and matching box art from
  `thumbnails.libretro.com`. The request reveals which systems you use and which box
  art images are fetched (and therefore game titles), plus your IP address. Lists are
  cached for a week; turn the setting off to stop all requests.
- **RetroAchievements** (off until you connect): your username, Web API key and the
  RetroAchievements ids of matched games are sent to `retroachievements.org`. Games are
  matched by hashing files on the device; files and hashes are not uploaded. The key is
  stored in Nomad's private preferences and removed on Disconnect. Keys are redacted
  from logged URLs.
- **Emulator links:** "Get …" entries open an emulator's official site in your browser.

## Imports, saves and second screens

- **ES-DE import** reads the folder you pick once and copies matching covers and
  videos into Nomad's private storage; titles, favorites and hidden flags go into its
  preferences.
- **Save sync** copies save files between the save folders you add and the sync folder
  you pick. Nomad does not upload anything itself; whatever syncs that folder (for
  example Syncthing or a cloud-drive app) decides where the files go. A manifest in
  each mirror records file hashes, times and the device model that wrote them.
- **Second screen** shows the selected game on another connected display.
Imported video themes are local files. Third-party emulators and media apps have
separate privacy practices.

Logs can contain error details and local paths. Review/redact logs before attaching
them to a public bug report. Public GitHub interactions are governed by GitHub's
own policies.

## Playtime and battery observations

Optional Android usage access lets Nomad count foreground app/emulator intervals
for games launched from Nomad. Only per-game totals, last-session duration and the
current launch marker are saved locally; raw usage events are not stored or sent.
Enable it in Settings → Playtime. Time before enabling access is not imported.
Emulator menus count, and games switched inside an emulator cannot be distinguished.
Sessions interrupted by a reboot may be omitted; Android may also expire old events.

Battery observations stay in memory and reset when Nomad's process restarts or
the charge state changes. Net battery watts use Android battery current and voltage,
not the charger's rated or wall-socket power. Time estimates prefer Android's time
to full, then observed percentage changes (at least 10 minutes and a two-point
change), then battery-gauge charge capacity and average current. All readings are
local. Estimates assume similar usage, include other apps and standby, and may
change as charging slows near full. This is not a battery health measurement.
