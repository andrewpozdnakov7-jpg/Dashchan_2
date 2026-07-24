# Dashchan_2 Codex Modes

This file is loaded through the repository-level `AGENTS.md`.

This file defines the working modes for Codex tasks in this repository.

## Default Rule

If the user does not explicitly request another mode, use:

```text
SOURCE PATCH ONLY
```

Never move automatically between modes. Each transition requires a separate
explicit user command:

- `SOURCE PATCH ONLY` -> `FAST LOCAL TEST`
- `FAST LOCAL TEST` -> `PUBLIC RELEASE`
- `PUBLIC RELEASE` -> `GITHUB RELEASE`

## 1. SOURCE PATCH ONLY

Default mode for source and documentation tasks.

Allowed:

- edit source files and documentation;
- inspect files and repository state;
- prepare a source-only report;
- create a source-only ZIP when the user explicitly requests one.

Forbidden:

- running Gradle;
- compiling;
- building APK/AAB files;
- installing to a device or emulator;
- running package scripts;
- creating GitHub Releases;
- pushing tags;
- publishing anything.

### Source ZIP handoff workflow

When the user explicitly requests a source ZIP for external compilation:

1. Freeze the intended working-tree state and create the ZIP before starting
   lengthy archive-content verification.
2. Run PowerShell archive preparation with `Set-StrictMode -Version Latest`
   and `$ErrorActionPreference = "Stop"` so that a failed copy or missing path
   aborts the operation instead of producing a partial staging directory.
3. Assume the archive command runs under Windows PowerShell 5.1 and the
   matching .NET Framework. Do not use newer runtime APIs such as
   `System.IO.Path.GetRelativePath`; calculate relative paths only after
   validating an exact source-root prefix.
4. Never combine `Copy-Item -LiteralPath` with wildcard paths such as `*`.
   `-LiteralPath` does not expand wildcards. To copy directory contents while
   preserving hidden files, enumerate them with
   `Get-ChildItem -LiteralPath <source> -Force` and pipe those items to
   `Copy-Item -Destination <stage> -Recurse -Force`.
5. Before creating the ZIP, run a mandatory staging gate: verify that the
   staging directory contains the expected top-level source directories,
   build scripts, and required files such as `Dashchan_2/build.gradle`.
   Abort if the staging directory is empty, incomplete, or unexpectedly
   wrapped.
6. On Windows, create every ZIP entry with an explicitly normalized `/`
   separator. Do not use archive helpers that preserve Windows `\` path
   separators. Build entry names from relative paths and replace `\` with `/`
   before adding them to the archive.
7. Before handing off the ZIP, run a mandatory fast layout gate: reject the
   archive if any entry name contains `\`, if required top-level entries such
   as `Dashchan_2/build.gradle` are missing, or if the source tree is wrapped
   in an unexpected extra directory.
8. As soon as the ZIP is closed, the fast layout gate passes, and its SHA-256
   is available, report its path
   and hash in a commentary update marked `created; verification in progress`.
   This lets the user start transferring or compiling it immediately.
9. Continue verification against that frozen ZIP: check readability, duplicate
   entries, forbidden or sensitive files, and exact source-file contents.
10. Do not change source files between creating the ZIP and completing its
   verification.
11. If verification fails, immediately mark that ZIP as invalid, explain why,
    create a replacement, and repeat the workflow.
12. In the final response, clearly report whether verification passed.

Creating this source-only ZIP does not authorize Gradle, compilation, APK/AAB
packaging, installation, publication, or upload.

## 2. FAST LOCAL TEST

Fast local test build. Use only when the user explicitly says:

```text
FAST LOCAL TEST
```

Allowed command:

```sh
./gradlew assembleNdebug -PnativePlayerFfmpegFlavor=ffmpeg8 -PnativeAbis=arm64-v8a
```

This mode produces a local test APK only. It must not publish anything.

Forbidden:

- creating GitHub Releases;
- uploading APKs or artifacts;
- pushing tags;
- changing release metadata for publication;
- running public release verification unless explicitly requested separately.

## 3. PUBLIC RELEASE

Local public/release artifact preparation. Use only when the user explicitly
says:

```text
PUBLIC RELEASE
```

This mode means local release work on the computer only.

Allowed:

- build release/public APKs locally;
- prepare local artifacts;
- verify the built APKs;
- report exact artifact paths.

Forbidden:

- creating GitHub Releases;
- uploading APKs or artifacts;
- creating or pushing tags;
- changing `update/data.json` to a published release entry;
- publishing anything to users.

After this mode, stop and ask:

```text
Релизные артефакты собраны локально. После ручной проверки запускать GITHUB RELEASE?
```

## 4. GITHUB RELEASE / PUBLISH GITHUB RELEASE

Real GitHub publication mode. Use only when the user explicitly says one of:

```text
GITHUB RELEASE
PUBLISH GITHUB RELEASE
```

Allowed after explicit confirmation:

- create a GitHub Release;
- upload APKs and release artifacts;
- create and/or push a git tag;
- update `update/data.json` with the real release entry;
- write real `length`, `sha256sum`, signing fingerprint, and download URL;
- commit and push release metadata.

Before starting, verify or request:

- `versionCode`;
- `versionName`;
- tag;
- release title;
- release notes;
- exact APK path;
- `sha256sum`;
- `length`;
- signing fingerprint;
- draft/prerelease/final status.

This mode must never start automatically after `PUBLIC RELEASE`.
