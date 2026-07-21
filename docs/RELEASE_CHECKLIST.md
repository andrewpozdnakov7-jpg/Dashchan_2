# Release Checklist

## Source

- [ ] Start from a clean `master` synchronized with `origin/master`.
- [ ] Confirm the tested checkpoint and review every change since the previous release.
- [ ] Update `versionName` and `versionCode` in `build.gradle`.
- [ ] Add exactly one matching entry to `metadata/versions.json`.
- [ ] Add Russian and English changelogs when the version should appear in the in-app changelog.
- [ ] Update README and other version-specific documentation.
- [ ] Validate all JSON files and run `git diff --check`.
- [ ] Scan tracked files for personal paths, usernames, IP addresses, tokens, and credentials.

## Build And Test

- [ ] Build the all-ABI `ndebug` APK from the exact source commit being released.
- [ ] Complete [TESTING.md](TESTING.md), including posting, attachments, media, rotation, and updates.
- [ ] Confirm package `io.dashchan2`, expected version, API 30 minimum, and all three ABIs.
- [ ] Compare APK permissions with the previous stable release.
- [ ] Verify the signing certificate against [SIGNING.md](SIGNING.md).
- [ ] Scan the APK for local paths, credentials, keystores, private keys, and unexpected signing files.
- [ ] Record byte length and SHA-256.

## GitHub Release

- [ ] Agree on Russian and English release notes.
- [ ] Create a stable tag in the form `VERSION-CODE`, for example `3.2.5-1085`.
- [ ] Upload only the signed all-ABI APK.
- [ ] Do not upload unsigned APKs, diagnostic reports, local properties, or signing material.
- [ ] Download the published APK and compare its SHA-256 with the local candidate.
- [ ] Verify Cyrillic release notes are not corrupted.

## Update Metadata

- [ ] Update the stable `update/data.json` only after the release asset is available.
- [ ] Set the exact version name, code, byte length, download URL, and certificate fingerprint.
- [ ] Preserve compatibility entries unless their removal is intentional.
- [ ] Commit and push the manifest separately.
- [ ] Read the published manifest from GitHub and confirm that the app discovers the update.

Beta releases use the separate `Dashchan_2_Update_Test` repository and must never replace the stable manifest until promoted.
