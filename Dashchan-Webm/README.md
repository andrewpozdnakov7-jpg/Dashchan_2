# Dashchan Video Player Libraries Extension

This extension contains the native video/audio libraries used by Dashchan's
built-in player.

In Dashchan_2 release branches this directory is bundled into the main
application source tree so the FFmpeg/WebM native build used by the tested APK
is published with the main app source. The built APK still ships these
libraries inside the main application package, not as a separate user-installed
extension.

## Dashchan_2 R17 Dev1 WebM2 Build

The current Dashchan_2 package is:

| Field | Value |
| --- | --- |
| Android package | `com.mishiranu.dashchan.lib.webm.test` |
| Library extension name | `webm2` |
| Feature flag | `lib.extension.dashchan2` |
| Version | `1.8-hevc-r8`, code `7` |
| Minimum Android | API 30 / Android 11+ |
| Native ABIs | `arm64-v8a`, `armeabi-v7a`, `x86` |

The regular non-parallel identity is still `com.mishiranu.dashchan.lib.webm`
with library name `webm`.

## Changes from the Original Version

- Migrated the Android project from the legacy `buildscript` setup with Android
  Gradle Plugin 3.6.4 and JCenter to the plugins DSL with Android Gradle Plugin
  9.2.1, Gradle 9.4.1, `google()`, `mavenCentral()`, and
  `gradlePluginPortal()`.
- Moved the Android package declaration from `AndroidManifest.xml` to Gradle
  `namespace` and `applicationId`, as required by modern Android Gradle Plugin
  versions.
- Updated the Android build baseline from compile/target SDK 30, Build Tools
  30.0.2, and NDK 21.3.6528147 to compile/target SDK 36, Build Tools 36.0.0,
  and NDK 29.0.14206865.
- Raised the minimum SDK from 16 to 30. Current WebM2 builds install only on
  Android 11 or newer devices.
- Updated FFmpeg from 4.3.1 to 8.1.2.
- Updated dav1d from 0.7.1 to 1.5.3 because FFmpeg 8.x requires dav1d 1.0.0+.
- Added legacy compatibility symbols required by the current Dashchan
  `libplayer.so`: `av_get_default_channel_layout`,
  `av_get_channel_layout_nb_channels`, and `avcodec_close`.
- Extended the minimal FFmpeg configuration beyond WebM/Matroska playback by
  enabling the MOV/MP4 demuxer and common MP4 codecs: H.264, H.265/HEVC, AAC,
  and MP3. Existing enabled codecs remain VP8, VP9, AV1 through dav1d, Vorbis,
  and Opus.
- Added a `parallel` build type for Dashchan_2 so WebM2 can be installed next
  to the original WebM extension.
- Kept native libraries for `arm64-v8a`, `armeabi-v7a`, and `x86`.
- Updated native build scripts for NDK 29 LLVM tools and Android API 30 for all
  supported ABIs.
- Added `-PexternalBuildDir=...` support so WSL builds can keep generated
  native sources and objects on the Linux filesystem instead of under `/mnt/c`.
- Prefers `ANDROID_HOME` / `ANDROID_SDK_ROOT` over `local.properties` so publish
  builds can avoid embedding a local Android SDK path in FFmpeg configuration
  strings.
- Adds fMP4 playback investigation notes in `docs/fmp4-playback-test.md`.

Together with the Dashchan player timestamp fix, this extension fixes the
tested fragmented MP4 playback-speed issue and enables the additional HEVC test
video set.

## Version Summary

| Component | Original version | Current branch |
| --- | --- | --- |
| Regular Android package | `com.mishiranu.dashchan.lib.webm` | `com.mishiranu.dashchan.lib.webm` |
| Dashchan_2 Android package | Not available | `com.mishiranu.dashchan.lib.webm.test` |
| Regular library name | `webm` | `webm` |
| Dashchan_2 library name | Not available | `webm2` |
| Dashchan_2 extension version | Not available | `1.8-hevc-r8`, code `7` |
| Android Gradle Plugin | 3.6.4 | 9.2.1 |
| Gradle | Not declared by the project | 9.4.1 tested |
| Maven repositories | Google, JCenter | Google, Maven Central, Gradle Plugin Portal |
| compile SDK | 30 | 36 |
| target SDK | 30 | 36 |
| min SDK | 16 | 30 |
| Android SDK Build Tools | 30.0.2 | 36.0.0 |
| Android NDK | 21.3.6528147 | 29.0.14206865 |
| FFmpeg | 4.3.1 | 8.1.2 |
| dav1d | 0.7.1 | 1.5.3 |
| FFmpeg demuxers | Matroska/WebM | Matroska/WebM, MOV/MP4 |
| FFmpeg video decoders | VP8, VP9, AV1 through dav1d | VP8, VP9, AV1 through dav1d, H.264, H.265/HEVC |
| FFmpeg audio decoders | Vorbis, Opus | Vorbis, Opus, AAC, MP3 |
| Native ABIs | `arm64-v8a`, `armeabi-v7a`, `x86` | `arm64-v8a`, `armeabi-v7a`, `x86` |

## Building Guide

1. Install JDK 17 or higher.
2. Install Gradle 9.4.1.
3. Install Android SDK Platform 36, SDK Build Tools 36.0.0, and Android NDK
   29.0.14206865.
4. Define `ANDROID_HOME` / `ANDROID_SDK_ROOT`, or set `sdk.dir` in
   `local.properties`.
5. Install Linux build tools: `curl`, `tar`, `xz`, `bzip2`, `make`, `ninja`,
   `meson`, and `python3`.
6. Run `gradle assembleRelease` on Linux x86_64.

For the Dashchan_2 WebM2 package, run:

```sh
gradle assembleParallel -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

When building from WSL with the source tree on a Windows drive, keep native build
outputs on the Linux filesystem, for example:

```sh
gradle assembleParallel \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86 \
  -PexternalBuildDir=~/dashchan-webm-build
```

The resulting APK file will appear in `build/outputs/apk`, or in
`$externalBuildDir/outputs/apk` when `-PexternalBuildDir` is used.

Additional files to be used by the client will appear in
`build/outputs/external`, or in `$externalBuildDir/outputs/external` when
`-PexternalBuildDir` is used.

### Build Signed Binary

You can create `keystore.properties` in the source code directory with the following properties:

```properties
store.file=%PATH_TO_KEYSTORE_FILE%
store.password=%KEYSTORE_PASSWORD%
key.alias=%KEY_ALIAS%
key.password=%KEY_PASSWORD%
```
