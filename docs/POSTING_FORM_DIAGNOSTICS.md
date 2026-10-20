# Opt-in posting-form diagnostics v3

Source implementation; regression fixtures prepared but not executed. This is a
bounded geometry/event recorder, not a screenshot, keylogger or automatic report sender.
The existing user-controlled Start / Stop-and-export workflow is unchanged.

## Recorded information

- App version/code, Android API and technical model information, capture timestamp,
  format version and keyboard viewport policy marker.
- Screen/viewport/window geometry, orientation, density/font scale, layout direction,
  multi-window/PiP, window focus, soft-input mode and system-window flags.
- IME visibility/insets from viewport and decor; status/navigation/cutout/gesture
  insets. Animation prepare/start/progress/end from the existing callback without
  installing a competing insets handler.
- Positions in screen/local coordinates, measured/current sizes, all paddings,
  width/height/weight/margins, visibility/clipping, attachment to window, pending
  layout, focus/enabled/click state, translation/scale/alpha/elevation. Resource IDs
  and developer view types identify nodes; no labels or view.toString are read.
- Fixed form nodes: editor/container, toolbar/wrapper, subject, identity field
  containers, checkboxes/options, action footer, original captcha widgets (including
  their geometry while lent to a separate dialog), attachment area and ancestors.
- Bounded attachment/editor subtree geometry, indexed by view position rather than
  filename, URI or attachment content. Tree limit/reached indicator is explicit.
- Actual top/bottom editor-container gaps in px and dp, measured against the toolbar
  and keyboard edge. gapsValidIme marks whether a keyboard inset exists. Transitional
  frames/transforms remain visible in the geometry; a single frame is not a settled
  acceptance result.
- Height-input events include the actual attachment-card count and selected gap:
  top 6 dp; primaryInline/primaryReserve identify the original captcha/send action
  immediately below the editor, with a 6 dp gap and no utility row while IME is visible.
  This replaces the earlier editor-to-keyboard 6/18 dp rule. An empty grid wrapper is
  not counted as a file. The same primary reserve drives sizing and final alignment.
- Height mode, fixed-line option and hidden-field booleans; editor character count,
  selection/caret/line metrics and edit counts only. No editor characters are read.
- Height policy inputs/results, ownership/docking transitions, footer reparenting,
  pre-draw remeasurement/waits and final scroll target/applied position.
- Native resize begin/end, focus/child-rectangle reveal delta, actual scroll changes,
  focus/window-focus/attach/detach, touch DOWN/UP/CANCEL action and pointer count.
  Manual scroll remains native. Recording does not consume input or request focus.
- The real posting form and its offline appearance preview use the same observer.

## Deliberately excluded

Message/subject/name/email/password/captcha values; hints, accessibility labels and
clipboard; filename/path/URL/URI/hash, attachment pixels or bytes; cookies/tokens;
device/account identifiers; keyboard package/key codes or keyboard input contents.
Never pass those values to the trace APIs: event names must be developer constants,
and details must contain only numeric/enum/geometry state.

## Bounds and lifecycle

Recording is opt-in. Geometry snapshots are at most once per 50 ms; deep trees at
most once per 250 ms (96 nodes, depth 4). Unchanged geometry/state is deduplicated.
Explicit native/IME/decision events are limited to 160 per second per form; omitted
event counts are logged. The in-memory rolling buffer is capped at 4 Mi characters;
old-entry removal count is retained in the export header. Logcat lines are chunked
below the platform truncation limit. Disk export remains on the existing worker.

Watchers/listeners and pending polling are removed on view close. The registry uses
weak root/observer references. A new recording resets deduplication for an already
open form. Inactive recording performs no snapshots, persistence or subtree traversal.
There is no unbounded view hierarchy dump, reflection into the native scroller,
layout correction, new background service or network upload in this diagnostic work.

## Capture a reproduction

Start recording before entering the form. Reproduce without and then with an
attachment, open/close the keyboard twice and wait for settling; if relevant type
dummy text, scroll, rotate, or change window size. Stop and export, keeping a note
of the visible symptom and approximate time. Review before sharing. Diagnostics
are a user-controlled local report, not a claim that the bug is already fixed.

Prepared fixture uses dummy canary values and a separate random cache directory;
it checks that exports omit field values/filename and that closing releases the
observer. No test has been executed as part of this source-only update.
