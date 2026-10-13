# Video startup frame handoff

The startup thumbnail and PiP transfer callbacks now wait for a frame commit and a
buffer stamp different from the new texture's untouched baseline. Both timestamp
and sampling transform are considered: zero or reset timestamps are legal. A
retained, previously rendered PiP texture is explicitly accepted without requiring
a new decoder frame. No pixel-color detection, fixed timer or update-count threshold
is used to decide readiness. View identity, surface identity and generation are checked before delivery;
detach cancels the pending observer callback. Late callbacks cannot uncover another
player. The native decoder, JNI contract, geometry and playback timing are unchanged.

Prepared unit tests cover empty-layer updates, a changed timestamp, a zero-timestamp
buffer with a sampling transform, non-monotonic timestamps, paused transfers and
immutable baseline storage. These tests do not prove device rendering behavior.

Device checks:

1. Open several cached and uncached videos repeatedly from the thread and gallery
   grid, then switch to neighboring videos. The preview should lead directly to
   video without an empty black rectangle. Genuine black content must still play.
2. Close during loading and immediately open another video. No old preview should
   cover the new player; Back and gallery gestures must keep working.
3. Check hardware decoding enabled and disabled, pause/seek/repeat, and reopen the
   last video with its saved position. There must be no thumbnail stuck over video.
4. Transfer playing and paused video into/out of PiP and rotate in fullscreen.
   Existing controls, video size and PiP geometry must not change.
5. In an extended diagnostic log, startup_preview_first_frame should include
   readiness=buffer_committed and follow surface_buffer_committed for the current
   texture/generation. surface_commit_waiting identifies an empty-layer commit;
   it is not a decoder error.

## Startup flash diagnostic capture

Enable the existing Extended log option before starting video diagnostics in
Experimental settings. Start recording before opening the gallery. Filter the
grid to videos, open several attachments, return to the grid and reopen the same
video; also try direct opening from a thread. Keep the capture short (20-40 seconds)
and save it immediately after reproducing the flash. Send the saved application
diagnostic log together with a screen recording and the flash time within it.
A separate logcat is optional for this non-crashing visual defect.

New schema=1 events are joined by texture ID (an in-process view identity, not a
device identifier), parent ID and, for commit probes, surface generation/probe:

- startup_handoff: thumbnail availability before clearing PhotoView, the attached
  texture, and preload/retained-handoff state. Missing thumbnails are explicit.
- startup_preview_trace: overlay attachment, first draws, skipped draws, clip,
  bounds/alpha, and removal reason. Removal includes draw counts and relative
  first/last paint times; -1 means no thumbnail draw commands were issued.
- startup_buffer_baseline and startup_commit_sample: the initial and sampled
  texture timestamps and sampling matrices, and the unchanged has_buffer decision.
- startup_commit_registered/ui_delivery/accepted/cancelled: commit scheduling,
  latency, UI delivery delay, stale identity checks and callback availability.
- startup_texture_update: the first eight texture updates, including whether they
  happen before or after first_frame_committed. Existing geometry events capture
  ancestor visibility/alpha at first preview paint and removal.

The original ordering/geometry events are limited to Extended log mode: at most eight draw-state/layout
reports per preview, eight commit probes and eight texture updates per surface
startup. These schema=1 ordering events do not read pixels or record paths, URLs
or user content. They do not change drawing order, decoder policy or readiness.

Important: paint_submitted means the drawable issued Canvas commands; it does
not establish that pixels were displayed. A frame commit means submitted
rendering, not guaranteed physical presentation, and a buffer stamp is not proof
that the video content is non-black. These events diagnose ordering and coverage;
they must not be interpreted as a verified fix or as pixel inspection.

Source-only handoff: compilation, unit tests, lint and device checks were not run.

## Optional startup pixel statistics

Extended video recording now also enables `startup_pixels schema=2` probes.
No new setting is required. The existing capture procedure above still applies.

- `source=producer_surface` with `ownership=diagnostic` uses asynchronous PixelCopy with
  a separately owned Surface wrapper of the current SurfaceTexture at texture
  updates 1, 2, 4 and 8. The player's request Surface is deliberately not used:
  native acknowledgement releases that Java wrapper as part of normal playback.
  The diagnostic wrapper remains owned until copy completion, then is released;
  busy admission, allocation/request failures and stale callbacks also release it.
  The native player's Surface and SurfaceTexture lifetimes are unchanged.
  This samples the most recently queued
  producer buffer, not necessarily the buffer currently latched by TextureView.
  The surface and player generation must remain current at completion.
- `source=window` samples only the video rectangle in the gallery's actual Window,
  not the whole screen. It observes an existing frame commit after the first
  preview paint and after preview removal. Transform/ancestor coordinates are
  mapped into window coordinates. `preview_closed_at_request` makes a delayed
  first-paint probe explicit rather than pretending it captured the old preview.
- Each request downsamples to a temporary 24x16 bitmap. Only average luminance,
  opaque fraction and near-black fraction (RGB maximum <=12, alpha >=250) are
  reported, without individual colors, spatial statistics, hashes or images.
  Temporary pixel arrays are cleared and bitmaps recycled after completion.
  Nothing is encoded, persisted or shared as an image.
- Requests include texture ID, phase or surface generation/update, trigger
  timestamp, request time, dispatch cost and asynchronous completion latency.
  Source-no-data, unavailable/protected/destroyed surfaces, stale owners,
  cancellation and busy admission are not reported as black frames.
- At most four copies may be in flight application-wide. There are at most four
  surface requests per surface startup and two window requests per preview.
  Detach cancels pending window observers. Late results from an ended recording
  are discarded; pending copies own their bitmap until PixelCopy completes.

For the repeated capture, confirm `startup_pixels schema=2`,
`source=producer_surface` and `ownership=diagnostic` are present. Successful producer
samples have `status=ok` plus brightness/black-fraction fields. `copy_error` is a
failed measurement, not a black-frame observation; retain its result code.
Keep the screen recording and extended log together. This repairs the diagnostic
probe, not the video-start flash, and is not device-validated yet.

These are diagnostic observations, not a fix or a new readiness gate. Black video
content remains legal; a high near-black fraction must never suppress playback.
Window PixelCopy observes a queued window buffer, not physical display scanout.
The two asynchronous sources may sample different frames. Interpret request times,
latencies and screen recording together; do not assert exact frame equivalence.
Readback may affect GPU scheduling even though no synchronous getBitmap, polling,
extra invalidation, fixed delay, decoder changes or playback wait were introduced.

Prepared VideoFrameStatisticsTest cases cover opaque black versus transparent,
black/white mixtures, near-black threshold, unchanged input and empty samples.
They do not validate PixelCopy, window transforms or device behavior. Compilation,
tests, lint and device checks have not been run for this source-only change.

## Preview coverage before the first texture layout

The previous preview drawable skipped its first draw when the newly attached
TextureView still measured 0x0. PhotoView had already been cleared at that point,
so a submitted frame could expose the dimmed gallery underneath even though the
media was completely cached and the first decoded buffers contained an image.
The buffer-commit readiness check did not cover this earlier layout gap.

VideoUnit now captures the thumbnail's PhotoView image rectangle, viewport,
local-to-overlay transform and alpha before clearing that PhotoView. The overlay
can paint this immutable geometry while the new texture remains unmeasured.
After texture layout it uses the actual video geometry, including transforms and
clipping. If the initial PhotoView was also unmeasured, the cached thumbnail is
fitted into the measured parent viewport as a fallback. Neither view is forcibly
measured, resized, faded or delayed to make the transition work.

Preview removal still requires the current player's committed decoder buffer;
the identity/generation and detach guards remain intact. There is no fixed timer,
black-pixel suppression, decoder/JNI change or new PiP behavior. Borrowed cache
bitmaps are not recycled by the preview. Extended startup draw events report
geometry=video, geometry=captured_photo or geometry=fitted_thumbnail, allowing a
zero-sized texture draw to be distinguished from missing preview coverage.

Prepared VideoStartupPreviewGeometryTest fixtures cover painting after clearing
PhotoView while the texture is 0x0, switching to live video bounds after layout,
independent snapshots with translation/clipping, and a portrait-thumbnail fit
without a measured PhotoView. These fixtures have not been compiled or run.

Device acceptance: repeat switching between fully cached neighboring videos,
including a video-only gallery filter; verify there is no empty frame between
thumbnail and video. Also check direct opening, zoom, pause/seek, Back during
initialization, fullscreen rotation and PiP transfer. Keep an extended diagnostic
log plus a screen recording if the flash remains. No compilation, tests, lint or
device validation was performed for this source-only bundle.
