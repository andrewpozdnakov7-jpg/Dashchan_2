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

- [ ] Build the all-ABI GitHub and F-Droid APKs from the exact source commit in one protected candidate run.
- [ ] Complete [TESTING.md](TESTING.md), including posting, attachments, media, rotation, and updates.
- [ ] Confirm package `io.dashchan2`, expected version, API 30 minimum, and all three ABIs in both candidates.
- [ ] Compare APK permissions with the previous stable release.
- [ ] Verify both signing certificates against [SIGNING.md](SIGNING.md).
- [ ] Scan both APKs for local paths, credentials, keystores, private keys, and unexpected signing files.
- [ ] Rebuild the F-Droid variant with fdroidserver and verify it against the signed reference APK.
- [ ] Record byte length and SHA-256 for both candidates.

## GitHub Release

- [ ] Agree on Russian and English release notes.
- [ ] Create a stable tag in the form `VERSION-CODE`, for example `1.2.3-1234`.
- [ ] Upload only the intended signed all-ABI GitHub APK and, when reproducibility is verified, the signed F-Droid
  reference APK.
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
