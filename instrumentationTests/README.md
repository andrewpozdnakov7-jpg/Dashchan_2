# Android smoke tests

These tests are separate from the existing JVM unit tests. They need a running
Android device/emulator and are **not** run by an ordinary APK build. All test
dependencies use `androidTestImplementation`; none is a production dependency.
The first audit pass adds four tests:

- installed `allowBackup` flag and compiled cloud/transfer backup exclusions;
- default AndroidX fragment instantiation for ten screen/dialog classes;
- actual Android Parcel/Bundle round-trip through `StackItem`, preserving forum,
  thread, posting and browser arguments;
- `MainActivity` -> About settings -> Activity recreation, observing the existing
  navigation queue without flushing transactions or changing production UI.

Run only on a disposable test installation/emulator. `MainActivity` initializes
real databases and can restore existing pages or run normal update checks.
Tests do not clear data, send posts, enable push, sign in, or download AI models.
Do not run them against a valuable daily-use installation. An emulator is best.

Example with an arm64 device visible to the build environment's `adb`:

```sh
./gradlew connectedGithubDebugAndroidTest -PnativePlayerFfmpegFlavor=ffmpeg8 -PnativeAbis=arm64-v8a
./gradlew connectedFdroidDebugAndroidTest -PnativePlayerFfmpegFlavor=ffmpeg8 -PnativeAbis=arm64-v8a
```

For an x86 emulator use `-PnativeAbis=x86`. Reports are under
`build/reports/androidTests/connected/`. Debug application/test APKs must have
compatible signatures; do not uninstall the daily-use app to resolve a test
signature conflict.

Existing JVM tests can be run independently:

```sh
./gradlew testGithubNdebugUnitTest testFdroidNdebugUnitTest -PnativePlayerFfmpegFlavor=ffmpeg8 -PnativeAbis=arm64-v8a
./gradlew lintGithubNdebug lintFdroidNdebug -PnativePlayerFfmpegFlavor=ffmpeg8 -PnativeAbis=arm64-v8a
```

`AndroidBackupPolicyTest` checks source policy, including forbidden distribution
overrides; `BackupPolicySmokeTest` checks packaged resources and the installed
backup flag. Neither proves behavior of every OEM backup tool or deletion of
old cloud copies. Run backup/restore transport checks only with disposable data.
These tests also do **not** cover process death, hardware decoding, PiP, all
forum authentication flows, or an actual Gemini Nano model download. Activity
recreation is not process-death testing.

The second pass adds real-GIF JNI close/draw and failed-construction checks,
idempotent video close before initialization, and default-constructor reflection /
generic callback-proxy check. These are regression smoke checks, not leak,
performance, full video decoding or PiP tests.

The default connected tasks above test Debug, which is not minified. They do
not validate R8's final Ndebug/Release output. In addition, externally build and
test an optimized APK: forum loading, captcha/Brotli, screen restoration, local
archives, video diagnostics and optional external WebM engine loading exercise
the retained reflection/JNI contracts. JVM tests and a successful APK build do
not replace this device check.

Extension resource tests add isolated configuration/resource replacement,
process-unique generations, legacy and owner-qualified chan URI reads, invalid
IDs, raw-stream failures, icon fallback, and package contexts without code
loading. Locale preparation verifies that the preference override does not
change the default Locale or trigger a ChanManager resource update. Its temporary
locale preference is restored in finally; the real-manager generation/key check
also restores its previous configuration. Use only the disposable installation
described above. These smoke checks do not install/update an external extension
or replace manual hot-update, drawer, captcha, and archive/export checks.
