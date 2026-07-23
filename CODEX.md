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
- prepare a source-only report.

Forbidden:

- running Gradle;
- compiling;
- building APK/AAB files;
- installing to a device or emulator;
- running package scripts;
- creating GitHub Releases;
- pushing tags;
- publishing anything.

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
