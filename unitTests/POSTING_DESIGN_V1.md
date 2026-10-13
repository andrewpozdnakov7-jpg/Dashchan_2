# Experimental posting design V1

Historical V1 geometry; superseded by POSTING_DESIGN_V2.md. The current implementation
uses V2 spacing, captcha bounds and option typography, not the values below.

Source-only adaptation of the supplied 400x860, 360x780 and IME 400x560 view specification.
No Gradle, compilation, automated tests, lint, APK or device screenshots were run/produced.
Exact rendered correspondence to the reference is not confirmed.

## Scope and preserved behavior

- Existing experimental preference/parent dependency and distribution boundary remain intact.
- The same EditTexts, native hints, selections, watchers, forum configuration, checkbox values,
  captcha controller, send validation, attachment controllers and draft ownership are retained.
- The initial compact captcha label is Капча in Russian and Captcha in the default locale.
- Ready PASS/SKIP states retain the checkmark instead of the painted label, with its existing
  6dp right inset and original force-refresh action. Loading retains the Material indicator.
  The still-measured label stabilizes the compact button's geometry across these states.
- Actual image/input challenges, errors, optional solver/cancel UI and large captchas retain
  the complete widget. They are not cropped into the 120dp compact action.
- Popup theme, glyph proxies, original formatting callbacks, safe viewport placement and
  INPUT_METHOD_NOT_NEEDED behavior remain. No return to showAsDropDown or an IME focus target.
- Existing fields hiding and compact/expanded/fixed editor-height preferences are not reset.
  Only the experimental automatic mode fills the resized viewport above the IME.
- Previous video frame handoff changes are unrelated and untouched by this UI patch.

## Layout implementation

Native Views only; no Compose, rasterized screen, dependencies or build-tool changes.
16dp outer padding (existing centering for windows wider than 600dp retained), 12dp gaps,
56dp subject minimum, 144dp editor-block minimum, 20dp editor radius and 1.5dp focused border.
Field text is 18sp. Editor tools have 8dp side/bottom padding and an 8dp attachment gap.
The toolbar is 64dp minimum with a 22sp normal title, restored on leaving the form;
native navigation/menu placement and existing safe insets remain platform-owned.

The 120dp compact captcha is centered vertically beside the original checkbox row.
Each original checkbox gets a presentation-only full-width row wrapper: two supported
options are two rows of at least 48dp, with a 20dp box and 12dp box-to-text gap.
Full labels are restored. Unsupported/hidden options reserve no row height. Additional
forum-supported options (including spoiler) remain, even though the reference shows two.
Natural measured labels determine when captcha must move above the full-width column.
Large-font rows wrap/grow; the full captcha widget is above the option block.

Formatting adapts its inline supported set to the viewport: italic first, then bold move
to overflow. Remaining extra/forum tools stay there. Widening restores inline commands.
At ordinary font size controls divide the available capsule width equally; at large sizes
intrinsic glyph bounds and at least 48dp touch targets take precedence. Extremely narrow
windows retain horizontal scrolling rather than inaccessible commands. SP has no permanent
selected-looking background because it is a one-shot formatting action.

Send is a 56dp-minimum, 16sp control with 28dp radius, 12dp top margin and 16dp safe-bottom
spacing. Enabled state remains controller-owned. No reference screenshot's disabled state
is forced into real posting.

When all visible fields, footer and minimum editor cannot fit, the same footer moves into
the form's ScrollView. There is no second IME inset and no forced fixed keyboard height.
Personal fields, attachment previews/counter, large captchas, user editor-height choices,
unsupported tools and large fonts intentionally alter the reference's example coordinates.

## Prepared coverage (not executed)

PostingCompactControlsTest: original controls/callback identity, typed ready captcha,
stable loading/ready dimensions, force refresh, real challenge input and 6dp check inset.
PostingDesignLayoutTest: narrow/large-font column layout, non-overlap, checkbox listeners
and state, hidden-row collapse, italic/bold overflow and action preservation after widening.
Existing PostingFormAppearanceTest: drafts/watchers/selection/hints and toolbar restoration.

Device acceptance: both themes; Russian/default labels; 360/320dp windows; font scales
1.3/2.0; keyboard and landscape; hidden subject/personal fields; attachments/counter;
captcha first load/reload/error/ready/real challenge; every supported formatting action;
each editor-height mode; leaving/reopening the form and returning to neighboring screens.
Only send a test post with explicit authorization to a suitable test board.
