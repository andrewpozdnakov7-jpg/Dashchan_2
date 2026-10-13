# Experimental posting form

Current presentation is documented in POSTING_DESIGN_V4.md. Descriptions below retain
historical implementation notes; V4 supersedes older glyph/overflow sizes and the
right-edge ready check. Existing settings, controller boundaries and V2 option geometry remain.

The original `experimental_new_interface_motion` preference retains its saved value and default.
It is now under Settings -> Experimental features -> New Material 3 interface.
The new `experimental_new_posting_form` preference defaults to false and is effective only when
the parent interface preference is enabled. Disabling the parent does not erase the child choice.
The choice is applied when opening a new posting view; the current view is not rebuilt mid-edit.

## Implementation

- GitHub-only presentation helper reuses the existing editable views, focus/text listeners,
  markup actions, captcha widget, send button, attachment and draft controllers.
- All original inputs retain native inline hints, not permanent label rows: subject, comment,
  name, email, password and captcha. Hints disappear while text is present and return when empty.
  Theme-derived outlined surfaces remain. Formatting
  lives at the bottom of the editor. The attachment action is now beside it rather than in the
  toolbar. Bold, italic, spoiler and quote are the preferred inline tools when supported;
  narrow widths move italic, then bold into the ellipsis without removing either action.
  Additional markup and forum-specific commands remain available there too.
- The row reserves horizontal gestures from DOWN before the swipe-to-open drawer can intercept
  them, including reversal from either edge. Vertical gestures are yielded to the form and normal
  command taps are not consumed. Ownership is released on UP/CANCEL.
- Short fields use a 56dp minimum container without a reserved caption line. The form's palette
  follows the user's accent; spoiler is a one-shot command without a selected-looking background. The captcha refresh
  icon appears only for the load action, not for skip/error labels.
- Captcha and send actions normally sit below the scroll viewport. If the usable window is too
  short to leave room for the minimum 144dp editor block and other visible fields, the same action panel moves into the form scroll container.
  Existing IME/inset ownership remains unchanged; no extra keyboard padding is applied.
- The initial captcha action follows V2: 168x56dp at 400dp safe-window width,
  128x56dp at 360dp, beside a vertical column of original
  checkboxes. CarryLayout itself and each original checkbox are retained; presentation-only
  row wrappers make every supported option occupy its own full-width touch row.
  It delegates click/long-click to the existing captcha widget.
  Loading replaces only the button's painted label with the same Material Expressive indicator
  used for refresh; the label stays measured, so the action row and editor do not resize.
  PASS and SKIP results keep the compact slot with an accent checkmark; acceptance comes from
  the existing typed captcha state, never from a translated label or just a downloaded image.
  The checkmark delegates to the original force-refresh action. Image/input, errors and optional
  solver/cancel states keep the complete existing captcha block. A presentation-only state hook
  updates visibility immediately, not via a new captcha
  request or send path. Checkbox values/listeners and unsupported-option visibility are retained.
- The ellipsis delegates to the existing button handlers and restores editor focus before acting.
  Its floating, theme-derived glyph grid retains U/O/S/SUB/SUP and forum commands without
  reparenting the original buttons. It is constrained above the formatting row in the visible
  viewport, with scrolling on short windows; Back/outside closes the popup, not the form.
  The popup is dismissed on command rebuild, geometry change, form pause or destruction. Attachment availability
  still comes from the current controller's capacity/import state. The isolated preview uses
  no-op attachment/captcha actions and cannot open a picker or access the network.
- The normal footer adds no extra form-bottom padding to the editor's
  16dp bottom margin. The outer editor/options gap is 16dp when no counter/attachments intervene;
  checkbox art/text remains centered in its 32dp normal-font row (at least 48dp for larger fonts).
  The narrow-window scroll fallback
  retains the footer's own 16dp safe-bottom padding, not extra form-bottom padding.
- The formatting popup uses the same opaque tonal palette as the formatting capsule, an accent
  outline and 18dp corners. Its glyphs copy the original formatting buttons' actual font size
  and typeface, retaining underline/overline/strike/subscript/superscript spans and callbacks.
- Field hiding and height preferences remain effective. The appearance preview is isolated from
  posting controllers, credentials, draft storage and network captcha loading.
- Toolbar colors, medium text weight and the enclosing toolbar shadow are scoped to the visible
  form and restored on pause/destroy. The existing animated navigation arrow now honors standard
  Drawable tint/state/filter APIs without changing its path, animation or navigation behavior.
  No global theme,
  activity recreation or dependency/toolchain changes are introduced. System bars retain existing
  application behavior. The layout is a native interpretation, not a verified pixel-perfect PNG copy.
- F-Droid uses an inert helper and retains the previous posting layout; the experimental entry
  and search results are unavailable there. Redesign-specific formatting labels are GitHub-only.

## Prepared, not executed

`Material3PostingPreferenceTest`: default off, parent/child dependency, retained choice,
flavour guard and search routing. `PostingFormAppearanceTest`: editor identity, selection,
watchers, hidden-field wrapper, error styling, command callback and toolbar restoration.
Additional view fixtures cover all inline hints, no caption rows, formatting reverse drag with
a competing swipe parent, vertical handoff, taps/cancellation and enclosing-toolbar restoration.
Compact-control fixtures additionally cover original callback/checkbox identities, captcha
expansion/collapse and cancel states, primary/extra formatting, and narrow-window row wrapping.
Additional fixtures cover stable captcha loading dimensions, glyph labels and bounded popup geometry.
The initial loading fixture also retains the XML-default visible cancel child inside a hidden skip
block: it must still use the compact Material loader, just like a manual captcha reload.

## Popup placement/IME follow-up

Formatting popup uses absolute, visible-screen bounds via showAtLocation, not showAsDropDown.
It cannot ask an ancestor ScrollView to scroll the editor into view. It remains focusable for
normal menu keyboard/Back handling, but INPUT_METHOD_NOT_NEEDED excludes it from IME targeting;
opening it must not hide/show the existing keyboard or change the form's viewport/height.
Editor selection and original command handlers remain intact. If the user actually resizes,
rotates, scrolls or changes the keyboard geometry, the stale popup is still dismissed safely.
Grid width uses the same separately rounded left/right padding as its views at fractional densities.

Prepared geometry fixtures cover a nonzero screen origin, keyboard-shortened viewport and fractional
density. Runtime acceptance: select text with the keyboard visible, open ellipsis repeatedly,
choose U/SUB/SUP, close with Back/outside, and repeat without the keyboard and in landscape.
No compilation, automated test execution or device validation was performed for this follow-up.

Prepared follow-up fixtures drive the actual CaptchaForm through SKIP/PASS/NEED_LOAD/IMAGE, verify
compact width, checkmark state, original force-refresh delegation and visible real-challenge input.
They also cover the single 16dp outer margin and copied glyph typography. These fixtures have not
been compiled or executed. Device review must include light/dark/custom themes, first load/reload,
caption status/force refresh, keyboard visibility, attachments/counter and narrow-window fallback.
No compilation, unit/instrumentation tests, lint or device run was performed for this source bundle.

## Device acceptance

The V2 design retains full 16sp option labels, with 12dp between the 20dp checkbox and text.
The captcha entry sits on the left, centered vertically beside the options. If full
labels or large fonts do not fit beside it, the captcha goes above full-width option rows.
Loading and ready states keep
the same measured width. The 24dp ready checkmark is 6dp from the right button edge,
and the button retains its 56dp minimum height. Prepared fixtures assert these dimensions
and original checkbox/captcha callbacks; they have not been executed.

V2 uses 16dp outer margins (wide-window centering retained above 600dp), 18sp field text,
16sp captcha/send text, 18sp formatting glyphs, a 64dp toolbar with a 22sp normal title,
and a 12dp subject/editor gap, 16dp editor/options/send gaps, and 24dp between captcha
and the option column. The two normal-font option rows have a 32dp center step.
The toolbar's
height, typography, colors and elevation are restored when leaving the form.
The editor tool row has 8dp side/bottom padding and an 8dp attachment/formatting gap.
Automatic height now fills the available viewport above the IME; compact, expanded
and fixed-line user choices retain their existing policy. No preference values are reset.
PostingDesignLayoutTest prepares narrow/large-font option layout, preserved checkbox
state/listeners, hidden-option collapse and overflow actions becoming inline again
after widening. These tests have not been compiled or executed.

1. Enable both switches. Open a reply in light and dark themes, with and without the keyboard.
   Inspect subject, comment, focus outline, formatting scroll in both directions, captcha, send
   and attachment actions. Reversing from the right end must not open the drawer.
   Check the inline paperclip, ellipsis commands and compact captcha button. On a narrow display
   or large font, captcha and option checkboxes must wrap rather than clip.
2. Check a long draft, selection/paste, formatting and hidden name/email/password/subject choices.
   Empty every field: its hint returns inside the field. Typing/restoring text must not leave an
   extra caption row. Switch among automatic/compact/expanded/fixed editor heights.
   Preview send only exits preview.
3. In an authorized test thread, check text-only and attachment-only sends, captcha reload/error,
   failed send, retry, and saved draft after Back/reopening/rotation. Check landscape with the keyboard
   and a large captcha: all controls must remain reachable by scrolling.
4. Disable the form switch: the previous form returns. Disable the parent: the child is unavailable
   without losing its saved choice. Re-enable the parent. Check neighboring screens retain their colors.
5. Capture real screenshots for visual review: light/no keyboard, light/keyboard, dark, attachment/captcha.
   Font scale 1.3 and 2.0, narrow and landscape windows, TalkBack and F-Droid remain device/build checks.
