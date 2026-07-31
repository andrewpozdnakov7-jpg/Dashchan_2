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

- [ ] Build the all-ABI GitHub APK and three single-ABI F-Droid APKs from the exact source commit in one protected
  candidate run.
- [ ] Complete [TESTING.md](TESTING.md), including posting, attachments, media, rotation, and updates.
- [ ] Confirm package `io.dashchan2`, expected version, API 30 minimum, all three ABIs in the GitHub candidate,
  and exactly one expected ABI in every F-Droid candidate.
- [ ] Compare APK permissions with the previous stable release.
- [ ] Verify all signing certificates against [SIGNING.md](SIGNING.md).
- [ ] Scan all APKs for local paths, credentials, keystores, private keys, and unexpected signing files.
- [ ] Rebuild all F-Droid variants with fdroidserver and verify them against the signed reference APKs.
- [ ] Record byte length and SHA-256 for all candidates.

## GitHub Release

- [ ] Agree on Russian and English release notes.
- [ ] Starting with 3.2.16, create the stable release tag from `VERSION`, for example `3.2.16`; historical
  `VERSION-CODE` tags remain unchanged.
- [ ] Upload only the intended signed all-ABI GitHub APK and, when reproducibility is verified, the three signed
  F-Droid reference APKs.
- [ ] Do not upload unsigned APKs, diagnostic reports, local properties, or signing material.
- [ ] Download every published APK and compare its SHA-256 with the protected-workflow candidate.
- [ ] Verify Cyrillic release notes are not corrupted.

## Update Metadata

- [ ] Update the stable `update/data.json` only after the release asset is available.
- [ ] Point `update/data.json` only to the normal GitHub APK and set its exact version, code, byte length, URL, and
  certificate fingerprint.
- [ ] Preserve compatibility entries unless their removal is intentional.
- [ ] Commit and push the manifest separately.
- [ ] Read the published manifest from GitHub and confirm that the app discovers the update.

Beta releases use the separate `Dashchan_2_Update_Test` repository and must never replace the stable manifest until promoted.
