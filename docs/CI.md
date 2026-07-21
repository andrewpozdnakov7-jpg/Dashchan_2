# Checks And Automation

The repository currently does not publish APKs through GitHub Actions. Release APKs are built on a controlled Linux/WSL machine, tested on a device, audited, and uploaded manually.

## Required Local Checks

Run from the repository root:

```sh
./gradlew test
./gradlew lintNdebug
./gradlew assembleNdebug \
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

## Future GitHub Actions

A future workflow may run Java tests, JSON validation, documentation-link checks, and non-native lint. Official signing should remain outside public CI unless a carefully reviewed secret-management process is introduced. Pull requests must not require access to release secrets.
