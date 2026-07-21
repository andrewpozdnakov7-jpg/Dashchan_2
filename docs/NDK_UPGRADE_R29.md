# Android NDK 29 Notes

Dashchan_2 currently uses Android NDK `29.0.14206865`, declared in `gradle.properties` and consumed by both the application and the bundled `Dashchan-Webm` build.

## Current Native Baseline

- minimum Android API: 30;
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`;
- FFmpeg: 8.1.2;
- dav1d: 1.5.3;
- libyuv: pinned commit `6afd9becdf58822b1da6770598d8597c583ccfad`;
- build host: Linux x86_64 or WSL.

The native scripts use the NDK LLVM toolchain and generate shared libraries consumed by `libplayer.so`. Compatibility wrappers are retained for FFmpeg APIs removed after the original Dashchan player was written.

## Upgrade Procedure

1. Change `androidNdkVersion` in `gradle.properties`.
2. Keep `Dashchan-Webm/build.gradle` aligned if it still declares the version directly.
3. Perform a clean all-ABI native build.
4. Inspect every packaged ABI and verify that all expected FFmpeg, dav1d, and libyuv libraries are present.
5. Run the media regression matrix from [TESTING.md](TESTING.md), especially fMP4, shifted timestamps, HEVC, speed changes, seeking, and picture-in-picture.
6. Check native library configuration strings for local paths before release.

Do not raise `minSdk` as a side effect of an NDK upgrade without a separate compatibility decision. Android 11 support is a project requirement.
