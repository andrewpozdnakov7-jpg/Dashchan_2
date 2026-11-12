# Experimental attachment compression

Source implementation, not yet compiled or verified on a phone. Enabled under
Experimental features → Attachment compression; disabled by default. The settings
screen uses the existing PreferenceFragment, checkboxes and standard dialogs.

## Conversion

- Media3 Transformer/effect 1.11.1, independent of the FFmpeg playback engine.
- The direct MediaCodec implementation is displayed as unavailable. It is not a
  second implementation or an alternative hardware switch.
- Automatic output prefers hardware HEVC on native Dvach, whose support was
  confirmed by the user. On other boards it prefers AVC. Explicit AVC/HEVC choices
  are available. Automatic HEVC initialization/export failure can retry with AVC.
- The encoder selector excludes software video encoders. AAC audio may use the
  platform's software codec. Input decoding depends on Media3/device support.
- Output is MP4 with AAC when audio exists, with HDR-to-SDR tone mapping. Source
  duration and audio are retained. Resolution may decrease proportionally without
  cropping or deliberate upscaling; original frame rate is not deliberately reduced.
- There is no universal encoder for every format accepted by the FFmpeg player.
  Unsupported device codecs/HDR fail with originals preserved.
- Optional **Always convert to H.264/H.265** is a separate default-off checkbox.
  With it enabled, even fitting videos are prepared for the selected output codec.
  The ordinary default remains compression only when a size limit is exceeded.
- A **Convert** action in a video attachment's existing options dialog can request
  conversion of that video while the main compression feature is enabled, without
  enabling the always-convert checkbox. Identity is checked again on activation;
  the request survives view recreation in the posting ViewModel. Repeating the
  action uses the original source, not a previously compressed output.
- Format-only conversion preserves dimensions/FPS when the device supports them
  and derives bitrate from the original rather than filling the size allowance.
  Spare total space can allow codec conversion to grow an input while remaining
  within both caps. This is not a lossless guarantee when codecs differ.
- Compatible SDR video can be transmuxed without re-encoding; default encoder
  settings, no video effects and KEEP_HDR avoid forcing a frame pipeline for SDR.
  Compatible AAC can also be copied. If the original is already MP4 with the
  chosen codec and compatible audio, it is reused unchanged. Result details label
  unchanged files and copied video streams. HDR, codec changes and size reduction
  require the applicable encoding path; no software video encoder is introduced.
- Large predicted quality/resolution reductions require confirmation. Confirmation
  permits low visual quality; it does not bypass a failed codec or invalid file.
- Byte targets include headroom; output size is checked. Up to four export passes
  may be needed, including the automatic HEVC→AVC attempt. Repeated passes are
  labeled and can reset the displayed percentage. Codec selection/capabilities
  may still impose a bitrate floor that makes the requested limit impossible.

## Limits

Empty settings mean automatic limits, not a hard-coded number. Native Dvach reads
`MAXFILESSIZE` from the selected board's HTML, and authenticated
`/api/captcha/emoji/id` supplies `maxFilesSize` when `result == 2`. As in the
browser, the effective board value is the maximum of these values. The request
uses the app's existing authenticated cookie on its configured host; no passcode
is guessed, displayed or logged. There are no posting requests in this lookup.

The site's value is KiB and applies to the **combined attachment size**, not to
each file independently. Current public `/mobi/` returned 40960 KiB during source
research; this observation is not stored as a default. Source references:

- https://2ch.su/mobi/
- https://2ch.su/static/js/board.js?cache=62
- https://2ch.su/static/js/captcha/EmojiCaptcha.js

Optional manual limits are decimal MB (1,000,000 bytes). A known board total caps
them. With an explicit manual total, a failed lookup can use that value. Other
boards currently require a manual total. No numerical defaults were invented.
Numerical limits remain subject to the user's pending clarification.

Non-video attachments remain unchanged and reserve their exact source-file bytes.
Eligible videos first receive the per-file cap; when the total still does not
fit, their byte budgets are reduced proportionally. If unchanged files exceed the
limits, the app does not silently transform or discard them. Output MP4 must be
advertised by the board. This implementation does not compress images, GIFs,
archives or standalone audio.

Explicitly converted videos can share unused total space up to their individual
caps after the baseline allocation; fitting non-converted inputs keep their full
reserved size. Turning on format normalization cannot by itself shrink an already
fitting input merely to reserve equal space for every video.

## Ownership and lifecycle

- One queue at a time, backed by a non-exported foreground service. Foreground
  startup occurs while the initiating posting UI is visible, before metadata and
  limit preparation can finish in the background.
- Android 15+ uses the mediaProcessing service type; earlier versions use
  dataSync. Existing wake-lock permission is reused, with bounded acquisition
  during actual export and release on every exit.
- Progress is weighted by video duration. Unknown progress is shown as preparation;
  100% is reserved for the completed queue. Notification includes cancellation.
- No Activity/Fragment/View is retained by the queue. The posting Fragment's
  ViewModel retains detached queue state through view/configuration recreation.
- Cancelling cancels the queue and releases temporary results; originals are
  unchanged. Conversion cannot submit a post. Sending waits while preparation,
  quality confirmation or conversion is pending.
- All outputs must succeed before the send snapshot can use them. Converted file
  hashes are retained by the draft cache, then by the existing posting-service
  handoff. The persisted draft keeps the original inputs for restoration.
- The completed queue's outputs can be previewed and originals restored. A missing
  output is handled at the send boundary with an error, not an uncaught exception.
- Input/settings signatures and profile generation guard stale results. Destructive
  profile changes invalidate the queue. Existing app security policies are not
  relaxed. Diagnostic events omit filenames, hashes, paths and cookies.
- Normal backgrounding retains the queue. Closing/removing the posting Fragment
  cancels it when its ViewModel is cleared. Process death does not resume the queue:
  saved original inputs are restored and conversion can be repeated. Abandoned
  exporter-only temporary files are removed on the next service creation.
- Android/OEM background restrictions, codec stalls, foreground-service quota and
  notification permission can affect execution/visibility. There is no guarantee
  of surviving process termination. A stalled export times out after 3 minutes
  without progress; Android's service timeout also cancels it.

## System test

The button queries advertised hardware codec capabilities and tries encoding then
decoding four synthetic 320×240 SDR frames at 10 fps. It never reads user media.
Reported support, successful probe, failed probe and unavailable probe are distinct.
Decoding cannot be probed with this clip when its encoding failed. This does not
certify arbitrary dimensions, frame rates, profiles, HDR or other applications.
The manufacturer-supplied hardware flag itself cannot be independently certified
by this probe. No such probe was run during source editing.

## Required device checks

1. Feature disabled: posting, editor, attachments, gallery, keyboard and Back retain
   their existing behavior in GitHub and F-Droid builds.
2. System test on hardware with and without HEVC encoding; rotate/leave the screen
   while testing, then check the distinct capability/probe labels.
3. Oversized AVC/HEVC input with audio, silent input, portrait rotation, WebM/MOV,
   60 fps and HDR. Check playback, duration, sound, colors and correct orientation.
4. With always-convert disabled and no manual request, a video below the limit
   remains untouched. Multiple oversized videos plus
   images respect both byte caps; verify exported files and server acceptance.
5. Dvach anonymous and valid/expired passcode accounts; compare automatic **total**
   limit with the browser. Passcode privileges have not been tested here.
6. Consent: proceed with a very small target, cancel, dismiss using Back. Originals
   must remain usable in each case. A technical encoding failure must remain safe.
7. Convert in background/with screen locked, rotate and return; observe overall
   progress, repeated-pass labels, completion and notification cancellation.
8. Cancel between videos, during an export and during result import. No partial
   result may be sent. Retry, restore originals, and edit/remove/reorder attachments.
9. Post the completed outputs; test posting failure/retry, unavailable cache files,
   insufficient disk space, process death and profile reset while converting.
10. Validate foreground-service notification/quota behavior on Android 14/15+ and
    OEMs. No device or emulator validation has been performed by the agent.
11. Use sample 01 below the limit: always-convert off → no conversion; enable it
    with HEVC selected → codec conversion if hardware supports it. Already-target
    AVC MP4 with AVC selected should be reused without another quality loss.
12. With always-convert off, use the attachment dialog's Convert action on sample
    08 (AVC/PCM MOV) with AVC selected: video can be copied, audio converted to AAC.
    Verify rotation with sample 03, selection changes, repeated commands, cancel
    and restoration. Check copied-stream diagnostics, limits and output file sizes.

Seventeen unit regression cases were added for byte allocation, duration-weighted
progress, limit parsing and manual MB parsing. Two additional Android
instrumentation cases cover ICU-specific parser compatibility. The agent did not
compile or execute these Java tests. A separate local native-ICU check reproduced
the unescaped-brace failure and accepted the corrected patterns; it is not a phone
test. Pattern compilation stays inside the parser method so a future parser
exception reaches the queue's normal error boundary instead of class initialization.
