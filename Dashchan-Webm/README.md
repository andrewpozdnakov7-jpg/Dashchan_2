# Dashchan-Webm Native Player Libraries

This directory contains the reproducible native-library build used by the Dashchan_2 video player. In current releases the resulting libraries are bundled directly into the main `io.dashchan2` APK; users do not need a separate WebM extension.

## Current Stack

| Component | Version |
| --- | --- |
| FFmpeg | 8.1.2 |
| dav1d | 1.5.3 |
| libyuv | commit `6afd9becdf58822b1da6770598d8597c583ccfad` |
| Android NDK | 29.0.14206865 |
| Android Build Tools | 36.0.0 |
| Native ABIs | `arm64-v8a`, `armeabi-v7a`, `x86` |

The enabled media set includes Matroska/WebM and MOV/MP4 containers; VP8, VP9, AV1, H.264, and H.265/HEVC video; and Vorbis, Opus, AAC, and MP3 audio. Compatibility wrappers expose legacy FFmpeg symbols still expected by the Dashchan native player.

## Main Application Build

The root project invokes:

1. `shared-prepare.sh` to download and verify pinned source versions;
2. `shared-build.sh` to build libraries for the requested ABIs;
3. `syncBuiltinWebmPlayerHeaders` to synchronize generated FFmpeg headers and symbol lists;
4. the main NDK build to link `libplayer.so` against those libraries.

Use the root Gradle Wrapper:

```sh
./gradlew assembleNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

Linux x86_64 or WSL is required. The generated sources and libraries are stored below `Dashchan-Webm/build` and are excluded from Git.

## Standalone Compatibility Module

`Dashchan-Webm/build.gradle` can still produce the historical standalone library package for compatibility testing:

- regular package: `com.mishiranu.dashchan.lib.webm`;
- parallel test package: `com.mishiranu.dashchan.lib.webm.test`;
- module version: `1.8-hevc-r8`, code `7`;
- minimum Android: API 30.

This standalone APK is not part of the normal Dashchan_2 installation or stable release process.

## Notable Changes From The Original Library

- migrated to Android Gradle Plugin 9.2.1, Gradle 9.4.1-compatible scripts, SDK 36, and NDK 29;
- updated FFmpeg 4.3.1 to 8.1.2 and dav1d 0.7.1 to 1.5.3;
- added MOV/MP4, H.264, HEVC, AAC, and MP3 support while retaining WebM codecs;
- retained all three project ABIs;
- removed machine-specific SDK paths from the public FFmpeg configuration string;
- added compatibility fixes for fragmented MP4, shifted timestamps, truncated files, playback speed, and HEVC frame delivery.

See [docs/fmp4-playback-test.md](docs/fmp4-playback-test.md) and the main [testing matrix](../docs/TESTING.md).
