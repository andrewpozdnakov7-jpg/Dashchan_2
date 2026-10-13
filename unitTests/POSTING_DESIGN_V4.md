# Experimental posting form V4

Source-only implementation. No Gradle, compilation, Java/native tests, lint, APK build,
device screenshots or runtime visual comparison were performed. Fixtures below are prepared,
not verified by execution. V4 overrides the previous formatting/captcha presentation;
the V2 checkbox/subject/editor/footer geometry remains.

## Formatting

Native original controls and handlers are retained. Attachment is 40x44dp; glyph cells
are at least 36x44dp with 16sp text. Group padding is 4dp and its radius 14dp. The group
fills the remaining editor width. Fixed-width direct actions precede a flexible spacer;
More is last at the trailing edge, so spare room is before More, not after it or divided
between tools. Existing horizontal touch ownership and overflow IME/viewport policy remain.

Supported direct order is B/I/U/O/S/SP/quote. When space shrinks, O, S, U, I, then B move
to overflow. Extremely narrow/large-font windows may move quote/SP too so that no action
becomes unreachable. Unsupported actions are never synthesized. SUB/SUP and other tools
remain in overflow. Cells grow with actual glyph dimensions; no text shrinking/ellipsis.
The popup uses 44dp-minimum cells and 8dp padding, at most three columns, preserving the
original actions and spans. More is disabled if there are no additional supported actions.

## Captcha state and motion

- The existing Material LoadingIndicator is retained. It fades into the real ready caption
  over 180ms. No artificial minimum loading time or HTML demo timer is used.
- A saved solution shows a centered 22dp native stroke check, an 8dp gap and Done/Готово,
  plus the theme's tonal fill. Content crossfade is 180ms; fill 220ms; check stroke 240ms;
  one .90 -> 1.05 -> 1.00 check accent lasts 360ms. No looping success animation.
- Retry/Повторить is shown on the real load error. Refresh/cancel remain original actions;
  network results and posting validation remain controller-owned. Invalidation/loading
  remove the stale ready appearance immediately, regardless of animation completion.
- SKIP is not alone proof of a solution. A saved SKIP answer is identified by existing
  challenge, foreground-response or input data; none of their values enters UI/logs.
  Plain no-captcha SKIP and authorization PASS retain the full original status/cancel UI
  instead of a fake solved check. Inline image challenges retain the image/input widget;
  entered text can be summarized without cropping or hiding that challenge.
- Captcha geometry reserves all captions and the check, remaining stable across ordinary
  loading/ready/done/error changes. At large fonts the measured minimum can trigger the
  existing stacked layout rather than clip. No preference values or field hints change.
- A single revision-owned animation is cancelled on state replacement, restore, pause,
  detach or close. Removed listeners cannot apply stale state. Same-state binding does
  not replay. Restored solutions render their final state without celebrating again.
- Motion respects the existing system-animation policy. Disabled motion applies final
  content/color immediately. Captcha state always follows actual callbacks; when a
  foreground solving dialog keeps the task active, no fake ready event is invented.
- Send colors animate for 250ms only on the real enabled false -> true transition.
  Animations never change enabled, validation, callbacks, answer data or submission.

The new helpers/resources are GitHub-only. Shared captcha/controller additions only
carry presentation metadata through inert F-Droid hooks; network and draft behavior stay.
The native stroke uses PathMeasure; animator cancellation follows Android's cancel/end
contract and strips stale listeners before cancellation:
https://developer.android.com/reference/android/graphics/PathMeasure#getSegment(float,float,android.graphics.Path,boolean)
https://developer.android.com/reference/android/animation/ValueAnimator#cancel()

## Prepared coverage

PostingCaptchaMotionStateTest: rapid transitions, stale revision rejection, repeated binds,
restore, disabled animations, close and loading/error precedence over an old answer.
PostingCaptchaAnswerTest: solution data vs authorization/no-captcha, with fixture data only.
Updated native view fixtures cover centered Done, unchanged callbacks, stable width,
error/authorization separation, full image inputs and no animation-driven enabling of send.
Existing editor identity/draft/selection, checkbox layout, toolbar restoration and popup
geometry fixtures remain; formatting widths/overflow expectations were updated for V4.

## Device acceptance

1. Enable the experimental interface and new posting form. Check both themes and the
   narrow/large-font/keyboard layouts. Supported wide tools include U/O/S; More is at
   the edge. Select text and invoke every inline/overflow command, then attach a file.
2. Load and solve the usual captcha: no footer jump, centered Done, one short check accent.
   Refresh, cancel, retry after an error and reopen/rotate with a saved solution; no stale
   check or replayed celebration. Authorization/no-captcha must keep its original status.
3. Try a forum with an inline image/input captcha: full challenge remains usable, including
   entering/clearing the answer. Disable system animations and repeat state changes.
4. Check the original send constraints and saved draft. Real test posts require authorization.

Previous video-frame cache/history and startup handoff patches are retained and untouched.
