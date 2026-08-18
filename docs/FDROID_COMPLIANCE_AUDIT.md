# F-Droid Compliance Audit

This document records the source-level review of the F-Droid distribution. It intentionally contains no local
filesystem paths, workstation identifiers, signing data, credentials, or private contact information.

## Distribution boundary

- Application ID: `io.dashchan2`.
- Display name: Slooop.
- Project license: GPL-3.0, with the full text in `COPYING`.
- Build task: `assembleFdroidRelease`.
- The F-Droid flavor disables the Google Play Services security-provider path.
- The F-Droid flavor disables application self-update endpoints, UI, background checks, and client APK requests.
- The experimental Bergamot translator is disabled and the prebuilt WebAssembly runtime is not packaged. A separate
  source-build and reviewer-policy pass is required before this feature can be enabled for F-Droid.
- Imageboard extensions remain separate APKs and require an explicit F-Droid-specific warning and confirmation
  before any download starts.

## Declared Java dependencies

| Dependency | Scope in the F-Droid APK | License |
| --- | --- | --- |
| AndroidX Activity, Core, Fragment, RecyclerView, DrawerLayout, WebKit, Browser, WorkManager | Runtime | Apache-2.0 |
| Jackson Core 2.22.1 | Runtime | Apache-2.0 |
| Brotli decoder 0.1.2 | Runtime | MIT |
| jsoup 1.15.2 | Runtime | MIT |
| TagSoup 1.2.1 | Compile-only / Android platform implementation | Apache-2.0 |
| LeakCanary 3.0 alpha 9 | `leak` build only; absent from `fdroidNdebug` | Apache-2.0 |
| JUnit 4.13.2 | Tests only; absent from `fdroidNdebug` | EPL-1.0 |

All declared repositories are Google Maven or Maven Central. No Firebase, Crashlytics, advertising, analytics, or
proprietary Google Play Services dependency is declared for the F-Droid flavor. This source review does not replace
the transitive-dependency result produced by `fdroid scanner`.

## Native components

| Component | Source selection | License |
| --- | --- | --- |
| FFmpeg 8.1.2 | Release archive with fixed SHA-256; LGPL-only configuration without `--enable-gpl` or `--enable-nonfree` | LGPL-2.1-or-later |
| dav1d 1.5.3 | Release archive with fixed SHA-256 | BSD-2-Clause |
| libyuv | Exact Git commit `6afd9becdf58822b1da6770598d8597c583ccfad` | BSD-3-Clause |
| GIFLIB decoder sources | Stored as source under `jni/src/gif/dgif` | MIT |

The F-Droid build accepts all three downloaded native source trees through `DASHCHAN_DAV1D_SOURCE_DIR`,
`DASHCHAN_FFMPEG_SOURCE_DIR`, and `DASHCHAN_LIBYUV_SOURCE_DIR`. Gradle then uses only those pre-provided trees and
does not fetch native sources.

## Assets

- Optional fonts are not bundled in the APK. The application downloads a user-selected font from the public
  Slooop add-ons catalog, verifies its declared size and SHA-256, and stores it in application-private data.
  The catalog provides the upstream source and per-family license link for every font. Manual TTF/OTF import
  remains available without network access.
- Material Design icons are declared as Apache-2.0 in the in-app notices.
- Project artwork and other project-authored resources are distributed under the repository GPL-3.0 license.
- Before submission, `fdroid scanner` and manual packager review must confirm that no undocumented third-party asset
  was introduced after this audit.

## Expected Anti-Features and content notice

- `NonFreeNet` is expected because the built-in integrations depend on third-party imageboard services. The final
  wording remains subject to F-Droid packager review.
- Imageboards can contain adult content. The current F-Droid Anti-Feature list has no separate `NSFW` key, so this is
  disclosed as a prominent content notice in the application description instead of inventing an invalid label.
- The extension installer is disclosed here because it downloads executable APK files. The F-Droid flavor requires
  informed opt-in consent before each selected batch and does not download anything when cancelled.
- `Tracking` is not expected: the F-Droid flavor does not check for application updates, send analytics, or upload
  crash reports. This conclusion must be confirmed by scanner output.

## Remaining gates before submission

1. Run `fdroid readmeta`, `rewritemeta`, `lint`, and `scanner` against the real fdroiddata recipe.
2. Build in an isolated F-Droid build-server environment without Gradle network access to native source archives.
3. Test the actual F-Droid APK on a phone, including cancelling and accepting the extension warning.
4. Compare the upstream reference APK with a clean fdroidserver rebuild and verify reproducibility by signature
   copying.
5. Audit the final tagged source and APK for local paths, credentials, signing files, logs, and private metadata.
6. Obtain explicit approval before pushing a fdroiddata branch or opening a public merge request.
