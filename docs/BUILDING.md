# Building Dashchan_2

## Supported Environment

The Android and Java parts can be inspected on any desktop platform, but a complete `ffmpeg8` APK build requires Linux x86_64. Windows users should build inside WSL with the project and native build outputs stored on the Linux filesystem.

Required tools:

- JDK 17 or newer;
- Android SDK Platform 36;
- Android SDK Build Tools 36.0.0;
- Android NDK 29.0.14206865;
- `bash`, `curl`, `tar`, `xz`, `bzip2`, `make`, `ninja`, `meson`, and Python 3;
- enough free space for three native ABIs and downloaded source archives.

Set `ANDROID_HOME` or `ANDROID_SDK_ROOT`, or create an untracked `local.properties` containing `sdk.dir=...`. Do not commit machine-specific SDK paths.

## Main APK

Use the checked-in Gradle Wrapper:

```sh
./gradlew assembleNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

The first run executes `prepareBuiltinWebmSources` and `buildBuiltinWebmLibraries`. These tasks download and build FFmpeg 8.1.2, dav1d 1.5.3, and the pinned libyuv revision.

To build only one ABI for a quick device test:

```sh
./gradlew assembleNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a
```

APK outputs are written under `build/outputs/apk`. Native intermediates are kept under `Dashchan-Webm/build` and `.cxx` and can be reused by later builds.

## Unit Tests And Static Checks

```sh
./gradlew test
./gradlew lintNdebug
```

The unit-test set is intentionally small; a release still requires the manual checks in [TESTING.md](TESTING.md).

## Clean Rebuild

```sh
./gradlew clean
./gradlew assembleNdebug \
  --no-configuration-cache \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

Cleaning removes expensive native outputs. Prefer an incremental rebuild unless investigating cache corruption or a native toolchain change.

## Common Failures

- `Linux x86_64 is required`: run the build in Linux/WSL, not Windows PowerShell.
- Android SDK path is not defined: set `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir`.
- Gradle daemon disappears: check available RAM and WSL limits; the project requests up to 2 GB for Gradle, while native compilation needs additional memory.
- Configuration-cache failure: retry once with `--no-configuration-cache` and record the failing task.
- Native source corruption: discard only the affected extracted source/build directory and re-extract a trusted source archive; do not patch generated third-party files blindly.

## Reproducibility Notes

The build pins Android tools and native source versions, but official APK signatures depend on a private key that is not stored in this repository. Generated files may also contain local build paths, so release APKs must pass the audit in [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).
