# Manual Test Matrix

Automated tests do not cover the full Dashchan UI, Android firmware differences, native playback, or live forum behavior. Test release candidates on at least one Android 11+ device before publication.

## Installation And Update

- clean installation;
- update over the previous stable version without losing settings;
- stable and beta update channels;
- update notification after launch;
- interrupted APK download and resume;
- installer launch and manual GitHub fallback.

## Forums And Posting

- Dvach boards, catalog, thread, refresh, and media loading;
- new thread and reply with text only;
- image, GIF, WebM, MP4, and MOV attachment selection;
- attachment preview and drag reordering;
- captcha and Passcode flows;
- queued retry after a rate-limit response;
- likes/dislikes on a board that supports them;
- AI-post hiding and other autohide rules;
- Kekabu communities, tags, popular authors, topical feeds, search, public links, stories, and comments;
- Pikabu sign-in through the official website, signed-out voting feedback, and user-initiated story and comment votes;
- account-available 18+ media after enabling it in Pikabu settings, including refreshing cached placeholders without bypassing age restrictions;
- experimental 4chan read-only channel when the network permits access.

Never use production testing to spam a board. Remove test posts when the service allows it.

## Media Player

- WebM, regular MP4, fragmented MP4, MOV, H.264, HEVC, VP8, VP9, and AV1 samples;
- malformed or shifted timestamps, truncated clips, high-resolution samples, and difficult clips that exercise frame recovery;
- repeated seek-bar and double-tap seeking while checking the first frame, playback state, and audio/video synchronization;
- pause/resume, mute, per-video and system-volume modes, gesture sensitivity, its test screen, and optional audio boost;
- every playback-speed option with and without audio;
- speed persistence settings;
- portrait/landscape rotation;
- picture-in-picture entry, playback controls, return to full player, close, and replay;
- next-media navigation after returning from picture-in-picture.

Do not commit user-provided test media unless redistribution is explicitly permitted and metadata has been audited.

## UI And Storage

- built-in and downloaded themes, plus duplicate-name JSON imports;
- settings search with navigation to results in different settings sections;
- post selection/copying on stock Android and modified ROMs where available;
- gallery filters, image clipboard copying, provider-based reverse-image search, and Android application fallback;
- background media saves and all file-name conflict policies;
- HTML/ZIP thread archives with originals, thumbnails, imported folders, and titles from Dvach and 4chan archives;
- Predictive Back with system gesture navigation;
- text scale from 75% through high accessibility values;
- launcher name/icon switching and custom shortcut creation;
- cache-size migration and cache clearing.

## Rotation And Lifecycle

Rotate the device in a board, thread, gallery grid, full-screen media, posting form, settings dialog, and referenced-post popup. Check that state is retained and no duplicate player or dialog remains visible.

## Reporting A Failure

Record the app version/code, Android version, device/ROM, exact steps, expected and actual results, network type, and whether the problem reproduces with default settings. Attach the crash section from logcat when possible, but remove personal data and unrelated application logs first.
