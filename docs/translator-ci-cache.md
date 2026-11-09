# Translator CI reuse and GitHub-only signing

GitHub app builds already use the pinned Mozilla JS/WASM downloads and their
Gradle download cache. F-Droid uses a separate source-built runtime. The CI cache
below reuses only that source-built output and never substitutes the Mozilla
download in a F-Droid build.

The reusable Bergamot workflow defaults to cache-first for compatibility CI.
Its exact key includes translation sources/locks/recipes/tests, the runtime
workflow (including its pinned container and flags), the root submodule manifest,
the actual compiler version, architecture and installed Debian package versions.
Application versions, unrelated app sources and application commit SHA are not
key inputs. Source preparation still verifies the pinned gitlink, dependency
revisions and allowed patch before a cache is considered.

Restores require an exact key, matching receipt, exact regular-file layout and
verification against the repository's runtime output lock. There are no partial
restore keys. Misses or rejected entries cause a fresh source build and a second
independent build with byte comparison; language-package smoke tests run on both
cache-hit and fresh-build paths. Failed/unverified output is not uploaded.

Only successful runs on protected master may populate the cache, after matching
independent builds. PRs and other branches only read a trusted default-branch
entry. The master push trigger warms the cache after a reviewed merge. Bootstrap
PRs can still be slow while no trusted entry exists. Expiry and eviction are
ordinary misses. An invalid existing exact entry is ignored and rebuilt, but not
overwritten (GitHub cache keys are immutable); it can be removed separately by a
maintainer. No signing secrets or application/user data are cached.

Actual F-Droid signed candidates pass `force_source_build: true`, skip binary cache
restoration and retain both independent source builds. A manual runtime dispatch
also defaults to this full verification mode. The F-Droid catalogue recipe keeps
its independent source build unchanged.

GitHub-only signing now explicitly handles the skipped optional F-Droid ancestor:
the sign job requires `always()`, no cancellation, a successful build, and master.
All existing confirmation, protected-ref, exact-source and certificate checks,
and the release-signing environment remain in place. A failed/cancelled build
cannot reach signing. No extra F-Droid APKs are needed for a GitHub-only release.

These CI changes do not change app versions, published APKs or updater metadata.
Pushing a PR runs checks; it does not dispatch signing or publish a release.
