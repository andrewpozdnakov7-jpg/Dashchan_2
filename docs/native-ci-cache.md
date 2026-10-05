# Native library cache in Android CI

Ordinary Android CI caches only FFmpeg and dav1d shared libraries,
generated headers and symbol stubs. JNI/player code, Java code, APK packaging,
unit tests, native policy tests, lint and APK checks are not skipped.
Stable Signed Candidate uses a separate trusted namespace (see below).
Upstream F-Droid builds do not consume GitHub caches.

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
can also warm other ABI sets. Checksums inside a cache do not establish trust
in an arbitrary cache producer; a release must not consume a PR cache.

To measure the improvement, rerun the same successful PR workflow without
changing its commit, ABI or distribution. Expect `Native cache HIT` on the
second run, with all original unit tests and lint still executed. Compare the
build step and total duration, including download/hash verification overhead.

## Stable Signed Candidate

The protected-master, explicitly confirmed stable workflow alone uses the
`signed-stable-v1-` namespace, without fallback keys. It does not restore
ordinary CI or PR entries. Beta does not read or write this namespace.
GitHub ref scoping excludes PR caches when the workflow runs on master.
Changes to trusted master workflows must continue to receive normal review.

The first stable candidate builds all three ABIs from source and freezes the
validated native outputs. Later identical-input stable builds may reuse them.
The source SHA gate and the separate signing job are unchanged. No keystore,
credentials, JNI/player output or APK is cached. A cache miss rebuilds normally.
Cache service failures remain optional, not source/build/verification failures.

Within that build job the three F-Droid reference splits explicitly reuse the
validated universal native set. Before each split it is restored and verified
again; a failed required restore aborts rather than skipping compilation with
missing libraries. Gradle's ABI filters select one ABI, and the existing signing
gate checks each final APK's exact ABI set. JNI, flavour dependencies and APK
packaging still run separately. The frozen cache is uploaded only after all
requested unsigned APK builds succeed; signing and certificate checks remain
mandatory before candidate delivery.

## F-Droid GitLab boundary

F-Droid's upstream `fdroid build` job already declares a Gradle dependency cache.
It does not retain our native output tree across jobs. Its CI file explicitly
requires buildserver-maintainer review for changes to this job. This repository
cannot enable a GitLab runner cache for upstream by editing its GitHub workflow.

Do not download GitHub-built native binaries in the F-Droid recipe or hide such
a cache in its Gradle dependency directory. Any native-output caching proposal
for upstream needs their approval and independent clean-build/reproducibility
validation. GitHub-built F-Droid reference APKs and upstream GitLab rebuilds are
distinct pipelines; enabling the former does not enable the latter.
