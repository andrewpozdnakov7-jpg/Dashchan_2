# F-Droid Build Preparation

Slooop uses `io.dashchan2` as its application ID and is licensed under GPL-3.0-or-later. The normal GitHub distribution and the future F-Droid distribution are built from the same repository and release tags.

## Native source policy

The player builds FFmpeg 8.1.2, dav1d 1.5.3, and libyuv commit `6afd9becdf58822b1da6770598d8597c583ccfad` from source. A regular build downloads these pinned sources and verifies the release archives or exact Git commit.

For an F-Droid build, the fdroiddata recipe must acquire the three source trees before Gradle starts and expose them through:

- `DASHCHAN_DAV1D_SOURCE_DIR`
- `DASHCHAN_FFMPEG_SOURCE_DIR`
- `DASHCHAN_LIBYUV_SOURCE_DIR`

All three variables are required for the network-free F-Droid path. `Dashchan-Webm/shared-prepare.sh` copies the supplied trees into the isolated Gradle build directory and does not invoke `curl` or `git` for those components.

## Planned distribution profile

A later source change will add a dedicated F-Droid build profile. It will preserve the `io.dashchan2` application ID while excluding the optional Google Play Services security-provider integration and the GitHub application self-updater. The normal GitHub build will retain its current behavior.

The final fdroiddata recipe, anti-feature declarations, screenshots, and reproducible-build comparison are intentionally handled after the source-only F-Droid profile is complete.
