# Dashchan

Android client for imageboards.

## Dashchan_2 R17 Dev Preview Fork

This working tree contains an unofficial parallel local build named Dashchan_2.
It is meant for testing beside the original Dashchan app and the original
extension set.

The current public preview candidate keeps `targetSdk 30` intentionally. The
minimum Android version is API 30 / Android 11+, but target SDK migration is a
separate future task because it can change permissions, storage, notifications,
foreground-service behavior, and system-bars behavior.

### Current Package Set

| Component | Package | Version | Minimum Android |
| --- | --- | --- | --- |
| Dashchan_2 | `io.dashchan2` | `3.1.4-r17-dev9`, code `1063` | API 30 / Android 11+ |
| Dvach extension | `io.dashchan2.chan.dvach` | `1.43-experimental-1.6-r10`, code `7` | API 30 / Android 11+ |

The Dashchan_2 build uses the `-dev9` version-name suffix.

Install these APKs together for the current preview:

- Dashchan_2 main APK;
- Dashchan_2 Dvach extension APK.

Starting from R17 Dev7, WebM2/FFmpeg/dav1d/yuv native libraries are bundled
inside the main APK. The separate WebM2 APK is no longer required for normal
video playback.

### What Was Added

- Parallel app identity through application ID `io.dashchan2`.
- Visible app label `Dashchan_2`.
- Separate file provider authority: `io.dashchan2.provider`.
- Explicit Android package queries for known Dashchan_2 extension packages.
- Dashchan_2 extension feature names:
  `chan.extension.dashchan2` and `lib.extension.dashchan2`.
- Known-package priority so Dashchan_2 Dvach/WebM packages are checked before
  the generic extension scan.
- Native-player metadata keys shown in the video metadata dialog:
  `player_build` and `player_ffmpeg`.
- Bundled WebM2/FFmpeg/dav1d/yuv libraries in the main APK, with the separate
  WebM2 APK kept only as a legacy/debug fallback.

### What Was Updated

- Raised the minimum Android version to API 30 / Android 11+ for the main app
  and current Dashchan_2 extensions.
- Updated the Android build to Android Gradle Plugin 9.2.1, Gradle 9.4.1,
  compile SDK 36, and Build Tools 36.0.0.
- Removed the old RenderScript gamma-correction path; the existing CPU path is
  used instead.
- Updated the WebM library lookup from hardcoded `webm` to the build-time
  Dashchan_2 value `webm2`.
- Kept fallback compatibility with old extension feature names:
  `chan.extension`, `lib.extension`, and the original `webm` library name.
- Fixed native-player timestamp handling for fragmented MP4 by using
  `best_effort_timestamp`, then `pts`, then `pkt_dts`, and rescaling through
  FFmpeg time bases.
- Fixed audio buffer duration calculation to use the actual output sample size,
  output channel count, and output sample rate.
- Added all-ABI FFmpeg 8 player builds for `arm64-v8a`, `armeabi-v7a`, and
  `x86`.
- Removed `QUERY_ALL_PACKAGES` from the packaged R17 Dev1 main APK; extension
  discovery uses explicit known package queries.
- Updated WebM2/native playback packaging to FFmpeg 8.1.2 and NDK
  `29.0.14206865`.
- Enabled Gradle configuration cache by default for faster local rebuilds.
- Completed the conservative minSdk 30 dead-code cleanup through R17 Dev4 and
  the final remaining old minSdk 30 guard cleanup in R17 Dev6.
- Integrated WebM2 libraries into the R17 Dev7 main APK and enabled the
  built-in video player by default for new installs.
- Added a Dashchan_2 update checker under Settings -> About -> Check for
  updates. It reads a static `updates.json` first, falls back to GitHub
  Releases when configured, and only opens a release/download page in the
  external browser.
- Added a first local unit-test skeleton outside the nonstandard main `src`
  source root.
- R17 Dev4 was smoke-tested on a OnePlus 13: app launch, WebM2/Dvach
  detection, problematic MP4/fMP4 playback, WebM, GIF, Dvach thread browsing,
  and posting with captcha. R17 Dev6 removes the remaining Java compiler
  warnings and old minSdk 30 compatibility guards; repeat the manual smoke
  matrix before wider posting.

### Identity Summary

| Component | Original Dashchan | Dashchan_2 |
| --- | --- | --- |
| Android package | `com.mishiranu.dashchan` | `io.dashchan2` |
| App label | `Dashchan` | `Dashchan_2` |
| Version name suffix | None | `-dev9` |
| Minimum Android version | API 16 / Android 4.1 | API 30 / Android 11+ |
| File provider authority | `com.mishiranu.providers.dashchan` | `io.dashchan2.provider` |
| Channel extension feature | `chan.extension` | `chan.extension.dashchan2`, fallback `chan.extension` |
| Library extension feature | `lib.extension` | `lib.extension.dashchan2`, fallback `lib.extension` |
| WebM library name | `webm` | Bundled in main APK, legacy fallback `webm2`/`webm` |
| Known Dvach package | Original extension package | `io.dashchan2.chan.dvach` |
| Known WebM package | Original library package | `com.mishiranu.dashchan.lib.webm.test` as legacy fallback |

## Features

* Supports multiple forums using extensions
* Threads watcher and reply notifications
* Automatic filter using regular expressions
* Image gallery and video player
* Archiving in HTML format
* Configurable themes
* Fullscreen layout

Read the [project wiki](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/wiki) for further information.

## Screenshots

<p>
<img src="metadata/en-US/images/phoneScreenshots/1.png" width="20%" />
<img src="metadata/en-US/images/phoneScreenshots/2.png" width="20%" />
<img src="metadata/en-US/images/phoneScreenshots/3.png" width="20%" />
<img src="metadata/en-US/images/phoneScreenshots/4.png" width="20%" />
<img src="metadata/en-US/images/phoneScreenshots/5.png" width="20%" />
<img src="metadata/en-US/images/phoneScreenshots/6.png" width="20%" />
<img src="metadata/en-US/images/phoneScreenshots/7.png" width="20%" />
<img src="metadata/en-US/images/phoneScreenshots/8.png" width="20%" />
</p>

## Building Guide

1. Install JDK 17 or higher.
2. Install Android SDK Platform 36, Build Tools 36.0.0, and NDK 29.0.14206865.
3. Define `ANDROID_HOME` / `ANDROID_SDK_ROOT`, or set `sdk.dir` in
   `local.properties`.
4. Use Gradle 9.4.1.

For the local Dashchan_2 build, run:

```sh
../tools/gradle-9.4.1/bin/gradle assembleNdebug \
  -PnativePlayerFfmpegFlavor=ffmpeg8 \
  -PnativeAbis=arm64-v8a,armeabi-v7a,x86
```

The resulting APK file will appear in `build/outputs/apk`.

Configuration cache is enabled by default. If a local Gradle task fails due to
configuration cache, retry once with `--no-configuration-cache` and report the
task name. See `docs/GRADLE_CONFIGURATION_CACHE.md` for checked commands and
cache maintenance notes.

See also `docs/SIGNING.md`, `docs/NDK_UPGRADE_R29.md`, `docs/CI.md`,
`docs/TESTING.md`, and `docs/RELEASE_CHECKLIST.md` for the current native
baseline, signing model, and verification flow.

### Build Signed Binary

Dashchan_2 local test packages are signed by the parent packaging script, not
by custom Gradle post-signing:

```powershell
.\tools\package-dashchan2.ps1 -Tag R17_DEV9_APPLICATION_ID -IncludeSourceArchive
```

The update checker manifest format is documented in
`docs/UPDATES_MANIFEST.md`; a fill-in example lives at
`docs/examples/updates.json`.

Run it from the parent checkout that contains `Dashchan`, `Dashchan-Webm`,
`Dashchan-Extensions`, and `tools`. See `docs/SIGNING.md` for details.

### Building Extensions

The current Dashchan_2 extension sources are maintained in the sibling
`Dashchan-Extensions` checkout. WebM2/FFmpeg libraries are bundled into the
main APK; see `docs/BUILTIN_WEBM_INTEGRATION.md` for the integration notes.

## License

Dashchan is available under the [GNU General Public License, version 3 or later](COPYING).
