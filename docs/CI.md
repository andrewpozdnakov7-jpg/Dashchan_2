# Checks And Automation

`Android CI` automatically builds the `github` arm64 variant for pull requests. A manual run from `master` can select either the `github` or `fdroid` distribution and arm64 or all supported ABIs. For `fdroid`, CI acquires the pinned native sources before Gradle and passes them through the pre-provided Gradle-property interface, exercising the same network-free Gradle path expected by fdroiddata. These APKs are unsigned test artifacts and are never published as releases.

Pull requests that change only `update/data.json` keep the required `Ndebug (arm64)` status but use a fast path: the workflow validates the update manifest and skips Java, Gradle, Android SDK, native tools, APK compilation, and artifact upload. Merges do not trigger a second build because protected `master` accepts changes only through an up-to-date checked pull request.

The protected `Android Signed Candidate` workflow builds and signs only the `github` all-ABI variant from `master`. It uploads a temporary candidate artifact after package, version, ABI, alignment, certificate, and checksum validation. It does not create tags, GitHub Releases, or update public metadata.

## Required Local Checks

Run from the repository root:

```sh
./gradlew test
./gradlew lintGithubNdebug
./gradlew assembleGithubNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

Also verify:

- `metadata/versions.json`, `update/data.json`, and `update/themes.json` parse as JSON;
- no unexpected file is staged;
- README and documentation links resolve;
- the APK contains all supported ABIs;
- certificate, package name, version, permissions, and SHA-256 are expected;
- no local paths, usernames, IP addresses, credentials, or private keys appear in release artifacts.

## Release Boundary

Pull requests never receive release secrets. A successful CI or signed-candidate run does not authorize publication; the explicit release modes and checks in `CODEX.md` still apply.
