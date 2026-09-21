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
- **Internet:** may retrieve album artwork from a URL supplied by an active media app.
- **Set wallpaper:** supports the explicit black-wallpaper action.
- **Wake lock / screen flag:** keeps the display awake while the launcher is visible.
- **Folder access:** Android's system picker grants access to folders you select.
- **Notification access:** optional; enables active media-session controls. The listener
  does not implement notification-content collection or upload.
- **Root:** optional advanced features can change performance controls, stop idle media
  apps, change PPSSPP configuration, and power off the device.

Media artwork requests go to the host selected by the media app; that host may see
your IP address. Nomad does not scrape game artwork or contact a game metadata API.
Imported video themes are local files. Third-party emulators and media apps have
separate privacy practices.

Logs can contain error details and local paths. Review/redact logs before attaching
them to a public bug report. Public GitHub interactions are governed by GitHub's
own policies.
