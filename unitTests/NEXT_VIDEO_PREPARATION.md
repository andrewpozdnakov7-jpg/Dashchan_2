# Next video: prepared decoder and start frame

## Scope

Source-only implementation. No Gradle, compilation, test execution, lint, APK build,
device validation or publication was performed.

The existing network preloader still owns downloads, user limits and per-forum routing.
The new preparation owner does not download anything. It prepares one next eligible video
only after the currently visible video's first buffer is committed (or an image is loaded).
Normal navigation chooses the adjacent item, without skipping images. TikTok uses the
same next-item selector/filter as its existing navigation. No settings, gestures, player
controls or UI styling are changed.

## Preparation and ownership

- Only a complete, atomically published cache file (or a local file) is opened.
  The downloader's mutable `.part` file is not treated as playable/complete. Partial-file
  warm-up would require separate range-session ownership and is not included here.
- At most one speculative decoder is retained per gallery. Rapid target changes coalesce
  while its worker exits; cancellation never joins native threads on the UI thread.
- Speculative work is disabled on low-RAM devices and limited to 64 MiB files and
  1920x1080 pixels (including portrait equivalents). Ordinary playback has no new limits.
- Native init stays at worker priority. A paused seek renders one frame to a worker-owned
  SurfaceTexture/EGL pbuffer; preparation never calls setPlaying(true) or acquires AudioFocus.
  The actual latched frame is captured, bounded to a 640px longest edge, and the texture is
  detached from its GL context before passing it to TextureView.
- The already initialized player and output surface are reused through the existing
  generation-aware surface-transfer machinery, not replaced with a second visible decoder.
  A bridge image of the decoded start frame covers the UI handoff until its committed buffer;
  the site's thumbnail is not used in this ready path.
- URI/file identity, length, modification time, seek policy, hardware preference and saved
  position must still match at selection. Reload, a changed/removed file, failed EGL/decoder,
  an incomplete seek or an unready target falls back to the unchanged startup-thumbnail path.
- Speculation is cancelled on pause, close, grid mode, forced interruption and PiP transfer.
  Prepared players never write playback bookmarks; only selecting a video registers it.
- No JNI ABI, native recipe, dependency, signing or cache-key change is introduced.
  Hardware/software selection, EOF, replay, audio-focus and PiP paths remain foreground-owned.

## Prepared tests (not run)

VideoPreparationIdentityTest: exact resource/file/policy match; changed resource, file,
size, timestamp, seek/hardware policy or bookmark invalidates reuse; EOF starts at zero.
VideoPreparationPolicyTest: file/pixel budgets, low-RAM fallback, portrait dimensions and
overflow protection. VideoFramePreparationTest: real EGL Surface buffer, RGBA channels,
top/bottom orientation and immediate cooperative cancellation, without a decoder/network.

These do not substitute for runtime tests of native pause/seek and same-surface adoption.

## Device checks

1. Fully cache two or three neighboring videos. Open the first and wait briefly, then switch
   forward. If preparation finished, the second starts from its real video frame rather than
   the site's thumbnail. Repeat in a video-filtered gallery and in ordinary/TikTok browsing.
2. Swipe rapidly before preparation finishes, reverse direction and jump from the grid:
   normal preview is allowed, but no wrong frame, black handoff gap, stall or background sound.
3. Repeat with hardware acceleration on/off, start-muted on/off, non-default speed and with
   another app playing music. Speculation must not request focus or affect its playback.
4. Pause, seek, replay after EOF, reopen a remembered video, reload, rotate/fullscreen, enter/exit
   PiP, close during preparation and return after a pause. Controls and saved positions retain
   their existing behavior; a remembered position invalidates a differently prepared frame.
5. Clear cache or use an uncached, corrupt, oversized/high-resolution video: unchanged normal
   playback/loader/error behavior. The optimization may fall back without blocking navigation.

Extended diagnostic markers: `gallery next_video_preparation` phases started, worker_done,
ready/awaiting_seek_commit and take with ready=true/false. `startup_handoff` records
prepared_frame=true/false. No resource URI, filename, content or local path is logged.

## Adjacent-page frame handoff follow-up

The ready decoder's independently copied frame is now published to the adjacent pager
page before selection, and to the temporary TikTok transition page if present. The actual
first committed foreground frame is retained too, so reversing to a recently viewed
video does not require another retained decoder. The per-gallery cache now retains
20 distinct recently viewed videos, the current video, and a protected upcoming window
matching the enabled video-file preload count (default 2, maximum 5). Disabling preloading
removes the upcoming retention window, not viewed history. Future frames do not evict
viewed history; unviewed speculation dropped from the window does not become history.
Only frame references are stored, bounded to a 640px longest edge. It does not download, write to
disk, acquire audio focus, seek the active player or retain extra native decoders.

Frames are keyed by exact URI, cache file metadata, requested start position and decoder
policy. Reload invalidates its frame and speculative decoder; pause/forced close clear
the owner references. Missing, mismatched or low-RAM cases still use the ordinary thumbnail.
Images/GIFs are not replaced with video frames. A late thumbnail callback cannot overwrite
a page that already displays a video frame. At startup that same non-recycled bitmap is
borrowed by the existing committed-buffer bridge. Eviction/cancellation never recycles
bitmaps still borrowed by page drawables; ART releases them after their last reference.

Prepared cases in VideoPageFramesTest cover forward/backward reuse, exact identity,
bounded viewed history/current/upcoming windows, setting shrink, revisiting, reload and clear.
VideoPageFrameBindingTest covers actual drawable binding,
ready-over-thumbnail priority (including immediate and late callbacks), rebind identity,
missing-frame fallback and a borrowed startup drawable surviving page cleanup.
They were not run or compiled. Device checks must cover
ordinary horizontal browsing as well as TikTok, rapid reversal/cancel, filtering, reload,
remembered positions, pause/resume, cache removal and hardware acceleration on/off.
Look for `gallery page_frame phase=published source=prepared/committed` and `phase=bound`.

Reverse-navigation follow-up: revalidate/rebind both neighbours immediately after
VideoPositionMemory.restoreOnce changes the single bookmark owner, and after the
foreground first-buffer callback even if its image was already cached. The temporary
outgoing bookmark may reject A's zero-position frame while selecting B; once B owns
the bookmark A's frame is valid again. Rebinding no longer depends on preparing a
third/next video. A valid page frame also skips the legacy wait-before-video delay,
without changing animation duration or starting a second foreground decoder.
Startup diagnostics now distinguish `page_frame=true` from `prepared_frame=true`.
The prepared two-video regression checks that the transient mismatch did not delete
the previous frame; it has not been run. Runtime check: cache just A and B, wait on A,
switch to B, immediately reverse to A, and repeat with position memory on/off and
EOF/replay. Capture `phase=refresh reason=bookmark_owner_changed` plus the binding
and startup source flags if a thumbnail remains visible.
No timing delay or change to gestures, controls, animations or public UI settings is introduced.

Retention is not decoding: only the existing nearest-next-video worker prepares a new
future frame/decoder. The remaining upcoming slots retain already available matching
frames; a downloaded file alone does not populate a frame. No five-decoder fan-out or
additional extraction worker was introduced in the retention change. Maximum owned
entries are 23 with preload count 2, or 26 with count 5; this is a bound, not a guarantee
that all slots contain frames. Existing low-RAM fallback and pause/close clearing remain.
Expanded-cache test fixtures were prepared but not compiled or executed.
The bounded committed texture snapshot uses Android TextureView.getBitmap(width,height):
https://developer.android.com/reference/android/view/TextureView.html#getBitmap(int,int)

Android ownership contracts:
https://developer.android.com/reference/android/graphics/SurfaceTexture#detachFromGLContext()
https://developer.android.com/reference/android/view/TextureView#setSurfaceTexture(android.graphics.SurfaceTexture)
