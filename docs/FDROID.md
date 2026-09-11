# F-Droid Build Preparation

Slooop uses `io.dashchan2` as its application ID and is licensed under GPL-3.0-or-later. The GitHub and F-Droid distributions are built from the same repository and versioned source. The official F-Droid package is available at [f-droid.org/packages/io.dashchan2](https://f-droid.org/packages/io.dashchan2/); a new version may reach the catalog after the corresponding GitHub release because F-Droid rebuilds and verifies it independently.

## Native source policy

The player builds FFmpeg 8.1.2, dav1d 1.5.3, and libyuv commit `6afd9becdf58822b1da6770598d8597c583ccfad` from source. A regular build downloads these pinned sources and verifies the release archives or exact Git commit.
If the primary FFmpeg release host is temporarily unreachable, source preparation falls back to the official FFmpeg GitHub mirror, resolves tag `n8.1.2`, and requires commit `38b88335f99e76ed89ff3c93f877fdefce736c13` before exporting the tree.

For an F-Droid build, the fdroiddata recipe must acquire the three source trees before Gradle starts and expose them through:

- `DASHCHAN_DAV1D_SOURCE_DIR`
- `DASHCHAN_FFMPEG_SOURCE_DIR`
- `DASHCHAN_LIBYUV_SOURCE_DIR`

All three variables are required for the network-free F-Droid path. `Dashchan-Webm/shared-prepare.sh` copies the supplied trees into the isolated Gradle build directory and does not invoke `curl` or `git` for those components.

An fdroiddata recipe can provide the same paths through Gradle properties named `dashchanDav1dSourceDir`,
`dashchanFfmpegSourceDir`, and `dashchanLibyuvSourceDir`. Environment variables take precedence. This allows the
recipe to use fdroiddata `srclibs` without patching the application source.

The recipe uses the conventional `fdroid` flavor and `release` build type (`assembleFdroidRelease`). The release
build type shares the production manifest and extension compatibility configuration used by the upstream `ndebug`
build. Gradle leaves this APK unsigned. The protected upstream workflow signs the matching reference APK with the
established developer key and `apksigner` from Android Build Tools 34.0.0.

F-Droid independently rebuilds the unsigned APK from the versioned source and recipe. Published GitHub reference
APKs are used only for reproducibility and signature verification. Publication with the upstream developer
signature is enabled only after F-Droid can copy the signature from the matching reference APK to its rebuild and
verify the result. The fdroiddata metadata uses versioned `Binaries` URLs and `AllowedAPKSigningKeys` for accepted
builds after the exact source, reference assets, and reproducibility result are available.

## Distribution profiles

The `github` and `fdroid` product flavors share the same source code and `io.dashchan2` application ID:

- `assembleGithubNdebug` retains the optional Google Play Services security provider, application update channels, automatic update checks, and package installer used by the existing GitHub distribution;
- `assembleFdroidRelease` uses a no-op security-provider source set, excludes the GMS option and application self-update interface, forces automatic update checks off even for migrated preferences, removes upstream application-update endpoints, and rejects client APK requests in the shared updater. The package installer remains available internally for separately installed imageboard extensions.

The final fdroiddata recipe must select the `fdroid` flavor so fdroidserver invokes `assembleFdroidRelease`. Anti-feature declarations, screenshots, and reproducible-build comparison are handled after this source profile is validated.

## Extension APK policy

The F-Droid distribution may still install separately packaged imageboard extensions, but it never starts an
extension APK download implicitly. Before downloading, it presents an F-Droid-specific confirmation that:

- identifies the downloads as extension APK files;
- states that F-Droid did not build or review those files;
- tells the user to continue only when the selected repositories are trusted;
- offers a normal cancel action which performs no download.

`BuildConfig.REQUIRE_EXTENSION_INSTALL_CONSENT` enforces this gate in the updater as well as in the user interface.
The application client itself remains excluded from this updater in the F-Droid distribution.

## Reproducibility

The native build disables GNU build IDs for the app-owned JNI libraries and externally built libyuv. This removes
the last known environment-dependent byte difference between independent GitHub and fdroidserver APKs. A final
comparison is still required for every release; source-level configuration alone is not proof of reproducibility.

The GitHub universal APK and the F-Droid ABI-specific APKs keep the same application ID and established developer
certificate, so a verified F-Droid package can replace the GitHub package in place without deleting application
data. The reference APKs are verification inputs rather than a separate end-user distribution channel.

See [FDROID_COMPLIANCE_AUDIT.md](FDROID_COMPLIANCE_AUDIT.md) for the current dependency, asset, Anti-Feature, and
privacy review. That audit is preparation material and is not a substitute for `fdroid scanner` or packager review.
