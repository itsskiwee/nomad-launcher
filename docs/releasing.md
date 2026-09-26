# Publishing a release

## Release rhythm

- **Unreleased first.** Every user-visible change adds a line under `## Unreleased`
  in the changelog in the same pull request, so a release is mostly renaming that heading.
- **Minor versions (0.5.0)** carry features. Each one goes out as at least one beta
  (`0.5.0-beta.1`), gets tested on real devices, then ships as stable.
- **Patch versions (0.5.1)** carry fixes only and can ship directly once tested.
  Four-part hotfixes (`0.4.1.1`) are for an urgent fix to a patch that just shipped.
- **Every release gets notes** in `docs/releases/vVERSION.md`, written from
  [`TEMPLATE.md`](releases/TEMPLATE.md): what changed for players, known issues, and
  what was and wasn't verified on hardware.
- Each release needs a higher `android:versionCode`, including betas, or Android and
  Obtainium will not offer it as an update.

## Beta builds

Beta and release-candidate versions (`X.Y.Z-beta.N`, `X.Y.Z-rc.N`) use the same steps
below with that string as the manifest `versionName`. `release.sh` marks them as GitHub
pre-releases. Publish a beta without `--latest`, so the README download link keeps
pointing at the stable build:

```sh
gh release edit v0.5.0-beta.1 --draft=false
```

Obtainium users who turn on "Include prereleases" for Nomad receive betas; everyone
else stays on stable. Ask testers to file a device report for each beta.

## Steps

Official releases are signed locally by the maintainer. Public CI builds and tests
a development APK with a separate development certificate; CI never receives the
release signing key. This keeps signing separate from code contributed by forks.

1. Update the manifest version name/code, `package.json`/lockfile, and changelog.
   The Android version display reads the manifest at runtime. Four-part Android
   hotfix versions such as `0.3.1.1` use `0.3.1-1` in npm metadata for SemVer compatibility.
2. Run `npm ci`, `npm test`, and `./build.sh --release`.
3. Install the signed APK on a test device and check startup, library, Favorites,
   settings, and permissions. Test a clean installation separately from upgrades.
4. Commit and push the release source; wait for GitHub Actions to pass.
5. Write release notes in `docs/releases/vVERSION.md` from the template, commit them, and run:

   ```sh
   ./scripts/release.sh 0.3.0
   ```

The script requires a clean checkout on `main`, an exact match to `origin/main`,
and local release credentials. It verifies the manifest version, builds a signed
APK, creates checksums and certificate metadata, pushes an annotated tag, and opens
a **draft** GitHub release. Review its assets and notes before publishing:

```sh
gh release edit v0.3.0 --draft=false --latest
```

Never overwrite a published tag or silently replace a published APK. If a build
needs correction, increment the version code/name and publish a new release.
Keep the original signing key securely backed up so future APKs can update installs.

The current signing identity is recorded in each release's `SIGNING-CERTIFICATE.txt`.
The SHA-256 checksum is for download integrity, not an independent provenance guarantee.
