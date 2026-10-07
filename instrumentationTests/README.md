# Android smoke tests

`PhotoEditorEngineTest` covers photo editor geometry/history/bitmap processing,
cooperative preview cancellation, detached session snapshots and the opt-in
legacy/new editor Intent contract. The switch test restores its preference in a
finally block; use a disposable test installation as described below. These
tests do not establish native UI appearance, keyboard layout or process-death
restoration. The new editor is disabled by default under Experimental features.

Editor regressions additionally exercise all fixed-aspect corners and edges,
opposite anchors, bounds, quarter-turns, free cropping, real MotionEvent pinch
commit/cancel and single-finger tail suppression, exported dimensions, undo/redo,
view-only pinch outside crop mode, and silent ruler synchronization after mirror.
The ruler test uses a standalone Android View; it is not an end-to-end Activity
visual test. Source contracts guard model binding, export selection colors,
landscape history access and the non-dismissing text validation handler.
These additions have not been executed in SOURCE PATCH ONLY mode.

Presentation regressions additionally cover final Matrix point agreement for all
eight orientations, clockwise 3-to-0 and four rapid retargeted turns, unchanged
document/history during transition samples, stale completion fences, positive
scale and intermediate bounds, reflection without an extra half-turn, retained
snapshot coordinate agreement, and premultiplied transparent-PNG blending. They
use real Android Matrix/Canvas/Bitmap and must be run on the disposable test
installation. The accessibility ruler test now checks the API-30 RangeInfo
constructor as well as its values after mirror/key actions.
These tests are prepared, not executed here; they do not measure smoothness,
GPU compositing on each device, Activity lifecycle races or panel layout visually.
Manually check rapid rotate/mirror/ratio/reset and undo/redo, crop Apply/Cancel,
fast filter selection with transparent PNG, save during a transition, Back,
background/restore, rotation, and system animation scales 0/0.5/1/2. Old editor
and posting attachment behavior must remain unchanged when the flag is off.

Phone-dialog regressions additionally check the explicit dialog palette against
light/dark host contexts. The six JVM `EditorPreviewSchedulerTest` cases model
slow work during continuous MOVE events, a bounded coalesced queue, progressive
publication, exact final UP, cancellation/undo epochs and stale callbacks. They
are prepared, not executed in source-only mode. On a phone, check three-line
text, black/white output colors, nested color cancel/cursor retention, keyboard
and landscape scrolling, JPEG/PNG export, and 2-3 seconds of uninterrupted
blur/mosaic/cover dragging: the effect must progress before lifting the finger.
Verify final coverage, undo/redo and cancelling the tool after preview results.

The standalone `python3 unitTests/check_photo_editor_sources.py` performs source
checks only (resources, lexical balance and attachment extras), not compilation
or Android tests. `PhotoEditorSessionFilesTest` and
`PhotoEditorIntegrationContractTest` are JVM checks for bounded private-cache
cleanup and opt-in integration. Abandoned inactive editor sessions older than
seven days are pruned on the next editor open; cache clearing or process death
before a queued session write finishes can still prevent restoration.

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

The final resource cleanup additionally checks stable URI/post serialization and
SHA-256 across resource generation changes, old query-bearing cached posts,
canonical archive/chan cache identity, and current-manager resolution from a
stale task fallback. The JSON/schema and public extension API are unchanged.
Legacy owner-less icon migration now also compares cached chan:///res/... icons
with owner-qualified icons using the owner of the compared thread. Smoke tests
cover deserialization, unknown/foreign owners, unchanged persistent serialization,
real comment changes, and network query identity. JVM source contracts verify
that PagesDatabase passes threadKey.chanName and gates MARK_EDITED on the result;
this is not an executed end-to-end database test.
These tests still do not install an old external extension; manually verify its
language/configuration and hot-update behavior. Extensions must fetch Resources
again after configuration changes, rather than retain a Resources snapshot.
