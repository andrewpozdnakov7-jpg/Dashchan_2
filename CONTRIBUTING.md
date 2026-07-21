# Contributing To Dashchan_2

Issues and pull requests may be written in Russian or English.

## Before Opening An Issue

1. Install the latest stable release and check existing issues.
2. Reproduce the problem with default settings when possible.
3. Record the app version/code, Android version, device/ROM, network type, exact steps, and expected result.
4. Remove names, account data, IP addresses, tokens, and unrelated applications from screenshots and logs.

Use the bug template for failures and the feature template for proposals. Support requests without reproducible project behavior may be redirected or closed.

## Pull Requests

1. Fork the repository and branch from the current `master`.
2. Keep changes focused. Do not mix a bug fix with dependency upgrades or unrelated formatting.
3. Follow the existing Java and resource style.
4. Add or update Russian and English user-facing strings together.
5. Update documentation and changelog metadata when behavior changes.
6. Run the checks described in [docs/CI.md](docs/CI.md) and complete the relevant parts of [docs/TESTING.md](docs/TESTING.md).
7. Explain what was tested and which Android versions/devices were used.

Do not commit generated APKs, build caches, media samples without redistribution permission, machine-specific paths, credentials, or signing material.

## Compatibility Priorities

- Android 11 support must remain intact unless a separate project decision changes `minSdk`.
- Existing installations must remain updateable with the established package and signing identity.
- Dvach posting, attachments, gallery, video playback, and updates are high-risk workflows and require device testing.
- Experimental features must remain opt-in until they have adequate field testing.

## Licensing

By submitting code or documentation, you agree that it may be distributed under the repository's GPL-3.0-or-later license. Contributions derived from another project must preserve required attribution and compatible licensing.
