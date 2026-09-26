# Contributing to Nomad

Bug reports, device compatibility reports, documentation fixes, and focused pull
requests are welcome. For a substantial feature, open an issue first so we can
agree on the intended behavior and scope.

You don't need to write code to help. A
[device report](https://github.com/itsskiwee/nomad-launcher/issues/new?template=device.yml)
from a handheld Nomad hasn't been tested on is one of the most useful contributions.

## Development

1. Fork and clone the repository.
2. Follow [the build guide](docs/building.md) to install the toolchain.
3. Create a branch and make a focused change.
4. Run the checks below and build a debug APK.
5. Add a line under `## Unreleased` in [CHANGELOG.md](CHANGELOG.md) for anything a player would notice.
6. Open a pull request explaining the user-visible change and how you tested it.

```sh
npm ci
npx playwright install chromium
npm test
bash tests/game-art.sh   # Java unit tests; needs the Android SDK
./build.sh --debug
```

On Linux, Playwright may need system packages: `npx playwright install --with-deps chromium`.
To use an existing Chromium installation, set `CHROMIUM_PATH=/path/to/chromium`.
Node.js 22+ and Python 3 are required for tests, not for running the Android app.

For UI changes, include a screenshot and check both populated and empty libraries
at landscape sizes. Check touch scrolling, tap targets, and keyboard focus. Keep
native work that could block (root commands, scanning, media decoding) off the UI
thread. Do not add network services, analytics, or automatic root actions without
an explicit design discussion.

`npm run screenshots` regenerates the README screenshots using the test suite's
original demo artwork. Real games and personal device screenshots are not needed.

## Tests

- `tests/boot-reveal.cjs`: startup readiness and fallback visibility.
- `tests/idle-cleanup.py`: simulated playback and visibility guards.
- `tests/ui.spec.cjs`: browser rendering, favorites, menus, and performance state.
- `tests/device-controls.py`: **manual only**, changes clock profiles on supported
  rooted hardware and restores Balanced. Never run this against a daily-use device
  without understanding the controller and its hardware requirements.

Browser tests stub Android bridges. They complement an APK/device test; they do
not prove emulator compatibility or root behavior on arbitrary devices.

## Style and scope

Use the existing Java, JavaScript, and CSS conventions. Prefer small functions,
explicit event handling, and no new runtime dependency unless it solves a clear
problem. Keep signing keys, credentials, imported covers, backups, and APKs out of
commits. Release APKs belong in GitHub Releases.

By submitting a contribution, you agree that it may be distributed under the
project's MIT License. Follow the [Code of Conduct](CODE_OF_CONDUCT.md).
