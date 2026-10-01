# Native library cache in Android CI

Ordinary Android CI caches only FFmpeg, dav1d and libyuv shared libraries,
generated headers and symbol stubs. JNI/player code, Java code, APK packaging,
unit tests, native policy tests, lint and APK checks are not skipped.
The signing workflow and upstream F-Droid builds do not consume this cache.

The exact key includes source file contents, ABI set, native build/prepare
scripts, the relevant Gradle recipe, cache helper, NDK package metadata,
compiler/build-tool versions and runner image. Application version and source
checkout path do not invalidate identical native inputs. No fallback keys are
used. ABI sets have separate entries; a universal entry is not silently reused
for a single-ABI build.

Every hit is verified against a manifest of SHA-256 hashes, required outputs
and ELF machine identifiers before `buildBuiltinWebmLibraries` is excluded.
Missing/corrupt entries rebuild normally. Cache upload happens only after the
build job's tests and APK checks succeed. Cache service upload/download failures
are optional; failed tests and builds are not.

Caches contain no signing material or credentials. GitHub scopes PR caches to
their merge refs. A master push affecting the native recipe/cache implementation
warms the default-branch cache for later PRs. Manual Android CI runs on master
can also warm other ABI sets. Signing deliberately remains a clean native build:
checksums inside a cache do not establish trust in an arbitrary cache producer.

To measure the improvement, rerun the same successful PR workflow without
changing its commit, ABI or distribution. Expect `Native cache HIT` on the
second run, with all original unit tests and lint still executed. Compare the
build step and total duration, including download/hash verification overhead.
