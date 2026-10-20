# Compact attachments in the experimental posting form

Source-only implementation based on Attachment Grid V1 with Compact Footer V2.
Only the existing experimental Material 3 posting form uses this view adapter.
The legacy form retains its original 1/2/4-column layout and controls. F-Droid has
a null adapter stub; the new views, motion, strings and tests are GitHub-specific.
No dependency, version metadata, send/captcha flow, draft format or forum-limit changes.

The primary success animation is now presentation-only: after the captcha is accepted,
a genuinely valid form can be submitted while Done/check/Send paint is still animating.
The existing click handler revalidates captcha/content/import/send state and finishes
the decorative transition before the ordinary send path. Feedback completion/cancel
does not grant availability, invoke a click or submit. Foreground captcha modal and
actual loading/invalid/sending state retain their existing guards.

## Layout and data

- Actual grid width selects 2 columns at the existing 144 dp minimum card width;
  otherwise 1. Card gap 8 dp. Total spacing from editor boundary to first preview
  is 6 dp: suppress the editor's existing bottom margin while cards are present,
  then supply 6 dp once in the grid. Restore the original editor margin when empty.
  Bottom grid spacing remains 12 dp; no empty-grid spacing.
- Preview96dp plus one shared footer row minimum48dp; radius12dp. The filename
  takes all remaining width through weight1, with8dp start padding and END ellipsis.
  Strip only the final nonempty extension for presentation; dotfiles/trailing dots
  and unexpected path/URI values remain intact. Full names remain available to
  accessibility and existing options. Never mutate holder names/hash/URI, drafts,
  MIME or upload names. No size/dimension/type line or extra badge is displayed.
  Two fixed48dp action targets contain20dp icons. A subsequent user-approved visual
  amendment adds individual36dp rounded tonal surfaces (theme-derived fill/foreground,
  native ripple clipped to the surface, no dividers). Press feedback paints6percent
  compression and12-to18dp corner rounding over100ms; release has a small spring-like
  return over300ms. Only Canvas paint is scaled; view bounds/transforms, hit testing,
  filenames and row size stay unchanged. Native pressed state owns feedback, without
  a touch interceptor or delayed click. Disabled edit remains disabled. Interface/
  system motion settings, detach, hidden views and window-focus loss settle/cancel
  the owned animator. No new animation dependency or model callbacks.
  The row grows for exceptionally large text
  without shrinking targets. Normal cards are144dp; two rows occupy296dp including
  the8dp inter-row gap. Conditional warnings/status/rating may add their required row.
  Incomplete
  rows have no placeholder views, and a single card keeps the normal column width.
- Full images are decoded on the existing thumbnail executor from attachment files,
  sampled for the stage, rotated by FileHolder and displayed FIT_CENTER. No cropped
  thumbnail/cache is used. Existing video thumbnail loading remains asynchronous.
- Edit/remove share the filename's row and retain their original holder callbacks. Reorder starts with a
  long press on the preview (including the video play overlay), with the platform's
  standard successful-long-click haptic. There is no separate reorder button.
  Short taps keep their original options/video-preview actions. All use existing
  controller listeners and AttachmentHolder identities. Unsupported editing is
  visibly disabled, not a fake video editor.
- Warnings/rating retain conditional controls; processing options/spoiler and full
  filename/size/dimensions remain in the existing attachment options dialog.
- No nested vertical scroller. With IME hidden, keep the existing maximum20percent
  attachment-height deduction and normal utility/footer order. When cards exist,
  put the same action panel immediately after the grid in the outer form, rather
  than pinning it to the window bottom and leaving arbitrary slack. The grid owns
  the entire12dp card-to-utility-row gap; remove the utility slot's usual10dp top
  inset only in this state. Empty forms restore normal footer placement/padding.
  Do not compensate for the smaller card footer with extra preview/field/spacer size.
  With IME visible,
  move the SAME primary action immediately after the comment container, before
  attachments. Hide the utility slot (paperclip/checks) without changing values or
  board-specific child visibility. A single 6 dp top padding supplies the editor-to-
  button gap; button height remains 56 dp, lower padding 6 dp. Automatic sizing reserves
  this 68 dp action area and the editor's actual formatting chrome, not attachment
  content. Earlier identity/subject fields remain scrollable above it. Keep 6 dp
  below the actual toolbar. Attachment count does not change IME editor height.
  This supersedes the previous editor-to-keyboard 6/18 dp preview-sliver rule.
  Restore the 16 dp form top padding without IME. Do not re-align on every scroll.
  Explicit compact/expanded/fixed text sizing remains user-controlled; these modes
  also use the inline primary action while IME is visible. The legacy form and F-Droid
  retain their existing behavior. Offline preview uses the same view-lifetime owner.
- Keyboard viewport v2: the native posting scroll view delegates automatic
  focus/rectangle reveal only while the new automatic editor owns IME alignment.
  It does not start a competing smooth reveal against pre-resize bounds. Alignment
  replaces any pre-existing smooth target using public ScrollView APIs; manual
  scrolling/fling remains native. Without an owner the ordinary/F-Droid path is
  unchanged. One sizing path now synchronizes ownership before calculating height,
  measures formatting chrome directly, and rechecks final geometry before drawing.
  The fragment retains internal caret scrolling but does not perform another
  parent reveal while that owner is active. No reflection, timed correction loop
  or repeatedly forced scroll position. Diagnostics v2 includes the full comment
  container and the active owner, plus a policy marker to identify this implementation.
  The top gap is measured from the toolbar's actual visible bottom: the existing
  56 dp ExpandedScreen inset may not cover the 64 dp (or large-text) new toolbar.
  Account for that overlap in both form padding and available input height.
  Focus-switch correction: do not gate editor alignment or IME motion on the
  root/ancestor isLayoutRequested flag. At pre-draw, validate actual measured/laid-out
  editor, viewport, form and primary-action bounds, the expected input height and
  adjacent action placement. A genuine mismatch requests the next layout; an
  unrelated persistent root request no longer prevents the single scroll commit.
  Repeated subject-to-comment focus changes must reveal the full primary action
  above IME while earlier fields scroll away. Once aligned, manual scrolling is
  not overwritten on each draw. Tiny-window and explicit-height fallbacks remain.
- Formatting uses spare width for supported board-specific commands too, including
  code/ASCII art/heading. When all commands fit without overflow, remove the overflow
  button rather than reserve its width. Preserve actual glyph widths, command identity,
  callbacks and the narrow-screen primary-command priority.
  Horizontal minimum tool width is now32dp instead of36dp; keep the16sp glyph size
  and44dp minimum height. Longer/large-font labels still get their actual required
  width. Remove the expanding spacer before overflow; if needed it follows the
  last inline tool directly. Subscript/superscript follow quote in the visible row.
  Nine ordinary32dp tools plus8dp row padding need296dp, fitting the312dp viewport
  recorded in115/06 without overflow. Narrow windows/extra tools retain the menu.
  After packing, distribute surplus width equally across visible tools instead of
  leaving it after SUP. Keep glyphs centered and the last tool at the4dp end inset;
  RTL mirrors the same rule. Overflow, when present, stays adjacent at the end.
  Opt-in formatting_pack events record numeric available/required widths and counts.
- Name/email fields now default to hidden. A one-time main-process migration applies
  the same choice to existing installations, including explicitly visible fields.
  The marker and both choices are saved together using the existing synchronous
  preferences transaction. Users can independently show either field again in
  posting appearance settings; subsequent launches do not overwrite those choices.
  Stored identity values, drafts, subject and password choices are not cleared or
  otherwise modified. The shared preference behavior applies to both distributions.

## Motion

Removal now has a separate view-lifetime presentation owner. The controller removes
the holder, cancels its thumbnail task and saves the changed draft immediately, using
its existing path. Before mutation, capture only the outgoing card (at most2Mi pixels,
window-local, never on disk) and the remaining live-card positions. After the existing
model rebuild commits, start the finite exit clock immediately: fade/shrink the outgoing
card to90percent over160ms. At pre-draw, capture only ready grid/card geometry and
translate live survivors to their new cells within the260ms transaction using
InterfaceMotion.STANDARD. Do not wait for DecorView.isLayoutRequested() to clear.
If local layout remains pending, exit/cleanup still completes; queued pre-draw cannot
retain an opaque ghost indefinitely. No snapshot is shown before its clock starts.
Outgoing bounds are grid-local and follow editor/grid displacement, rather than
covering a newly expanded editor at stale window coordinates. Native scroll clamping
before destination capture is safe; scrolling after capture or a changed card target
settles the animation. Numeric opt-in diagnostics include removal_prepare/commit/
wait/reflow/finish, never names or image content. No delayed removal,
duplicate click callbacks, reordering or restoration of a deleted holder. The last
card fades with the same empty-state spacing restoration. Clip the outgoing image
to the previous visible scroll viewport. Rebuild/model replacement, scroll, resize,
focus loss, detach, close and subsequent actions clean up poses, listeners and image.
Disabled motion/detached views/capture failure use the immediate existing UI path.

IME form changes use one presentation owner for the new form. It commits the real
layout and native scroll target first, then translates live form/primary views from
their last displayed positions with InterfaceMotion.STANDARD over260ms. Thus a
visible subject scrolls away smoothly when the automatic comment viewport takes
focus. The utility row fades over160ms; its outgoing paint is clipped live drawing
in the window overlay, not a bitmap or a second interactive row. No text scaling,
focus request, delayed captcha availability, send action or preference mutation.
The legacy ImePanelMotion callback is not attached to the new form, so two owners
cannot translate its primary panel. Reverse transitions start at the presented pose;
touch/resize/focus loss/detach/close settle and clear overlays/listeners. Disabled
interface/system animations commit the same layout without animation. Manual touch
ends presentation before native scrolling handles the event. Diagnostics include
ime_form_motion with numeric deltas and duration only.

The player editor's current motion parameters and InterfaceMotion policy are reused:
lift 120 ms, source dim 100 ms, hover 100 ms, drop 260 ms, cancel 220 ms, STANDARD
easing, 1.06 scale, 8 dp lift. No player ordering/model dependency was introduced.
An overlay inside the same window draws the only drag image; the native shadow is
empty. It follows the actual grab point and supports form-edge autoscroll.

Only a valid DROP changes order/saves the draft. A->C in [A,B,C,D] produces [B,C,A,D],
not a swap. Native drag ends before card views are reparented. Drop/cancel settle
draws old/new bounds together; cancellation does not undo an already saved order.
Detach/resize/model replacement cancel presentation and remove callbacks/overlays.
Accessibility provides earlier/later actions on the preview without requiring a drag gesture.

## Verification boundaries and device checklist

Prepared but not executed: attachment-geometry/display-name tests, attachment view tests,
editor-height unit cases, formatting packing fixtures and keyboard
viewport/scroll fixtures, plus an isolated preferences-migration fixture. The viewport fixture
injects the platform visibility decision into real view
layout; it does not test a live IME. No compilation, Gradle, lint or device tests.
Source-only ZIPs are produced only on request. Static review is not a device-test result.

On a disposable test draft (do not send a real message solely for testing):

1. Enable Material 3 and the new posting form. Attach 0/1/2/3/4/more files within
   the forum limit; compare portrait, landscape, split-screen and large text.
2. Check tall screenshots, panoramas, squares and rotated photos: entire frame
   visible, no crop/distortion. Test a video and a non-image file too.
3. Use edit/remove/options/rating/spoiler; warnings and unavailable-file states
   must remain visible. Tap a preview/name for full file details.
4. Reorder across both rows, cancel a drag, drop on itself/outside, and autoscroll
   both directions. Reopen the draft and verify the exact same file order/options.
5. Leave/rotate during drag/drop; disable system animations. No ghost, stale alpha,
   wrong-file thumbnail/editor result, duplicate save or discarded committed order.
6. In automatic height mode, open/close the keyboard with 0/1/many attachments.
   The editor including formatting has 6 dp above; its original captcha/send button
   follows 6 dp below, before attachments, without paperclip/checks. Their values
   must survive opening/closing IME. Verify cursor/selection, changing
   keyboard height, rotation, preview, and return from attachment editing. Manually
   scroll to attachments: the form must not snap back on every frame. Hidden-IME
   height/gap and explicit height modes should stay unchanged.
7. Long-press the image and video play overlay: one drag, no options/play triggered
   on release after a successful long press. Short taps must retain those actions.
   Use TalkBack earlier/later actions on the preview and both bottom action targets.
   Check one48dp footer with filename/edit/remove; short captions have no extension,
   long captions ellipsize only at the end, and full original names remain in details.
   Confirm144dp normal cards,8dp inter-row spacing and12dp to the utility/check row
   in both short and tall windows. Enlarge font and test conditional warnings/rating.
   Check the36dp edit/remove tonal surfaces in light/dark/custom themes, native
   press/ripple and release, drag-to-scroll cancellation, disabled video editing,
   rapid actions, rotation and animation-off behavior. Targets stay48dp throughout.
   Delete first/middle/last cards and two cards quickly: immediate model change,
   outgoing fade/shrink and smooth cell filling, no removed item in a reopened draft.
   Cancel presentation by scrolling, rotating or leaving; disable system animations.
8. Check formatting on a forum supporting code/ASCII art/heading: commands that fit
   should appear inline, otherwise remain accessible in overflow. Resize/rotate and
   enlarge text; nothing should overlap or lose its original formatting action.
9. On a new install or first update to this patch, name/email should be hidden.
   Re-enable either separately in posting appearance settings; reopen/restart and
   confirm that the choice persists without losing saved field values or drafts.
10. Repeat diagnostic case 115/05 without and with an attachment: open/close IME
    multiple times; after settling no extra animated upward scroll. Check 6 dp above,
    6 dp from the full comment container to the original primary action with or
    without attachments, then manually scroll to attachments
    and back without the owner snapping it on every frame. Diagnostics should say
    viewportPolicy=keyboard_viewport_v2 and include commentContainer/viewportOwner.
