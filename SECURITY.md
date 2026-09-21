# Security policy

## Supported releases

Security fixes target the latest published release and `main`. Older releases do
not have a separate maintenance branch. Nomad is an early project with one primary
maintainer; no response-time guarantee is offered.

## Report a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/itsskiwee/nomad-launcher/security/advisories/new).
Include the affected version, Android version, reproduction steps, expected impact,
and a minimal proof of concept. Avoid including private signing keys, personal ROM
paths, or unrelated device data.

Do not publish working exploit details in a public issue before the maintainer has
had a chance to assess and address them. Ordinary bugs belong in the issue tracker.

## Relevant boundaries

The WebView loads a bundled local interface and exposes native bridges. Changes to
navigation, content loading, file access, and bridge input handling deserve careful
review. Root features can affect other apps and system settings and are opt-in.
Signing credentials are kept outside version control and public CI.
