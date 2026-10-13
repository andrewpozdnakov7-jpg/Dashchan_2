# Message input appearance

The settings live at Interface / Submission form / Message input form.
The existing `hide_personal_data` key and its stored boolean are retained as the
fallback for the independent `posting_hide_name` and `posting_hide_email` keys.
Each field inherits the old choice until its own option is set. The independent
`posting_hide_password` option defaults to true. It hides only the field, not
deletion-password support or a custom password retained in a draft. The forum's
user-icon selector remains available. Raw draft values are retained. Hidden
name/email/subject fields are omitted by the existing visible-field send boundary.

Height modes:

- Automatic (default): previous sizing, including available-space fill without
  the keyboard and a 4-to-9-line range with the keyboard.
- Compact: 4-to-9-line range regardless of keyboard state.
- Expanded: 8-to-16-line range, subject to viewport limits.
- Fixed: 2-to-20 configured lines, clamped to the available viewport.

These are independent presets, not verified pixel replicas of other clients.
All sizing is shared by the real form and the offline preview. Only the real
form has captcha, attachments and posting controllers; the preview uses the same
editor layout, but its Send button only closes the preview. Preview fields do
not load saved identity values, passwords or posting drafts. Each new preview
starts with 15 localized sample lines; restored view state preserves edits made
in the preview. Its personal-data section is hidden if all its fields are hidden.

`EditorHeightPolicyTest` covers the previous automatic bounds, compact and
expanded ranges, fixed-height stability and short-window clamps. Tests were
added, not run as part of this source-only handoff.

## Manual checks

1. Try all four modes in Preview form, with short and long text. Open/close
   the keyboard and rotate the screen. Fixed height must not grow with text.
2. Choose a fixed line count, return to settings and restart the app; the mode
   and count should persist. Back and Send both close the preview; nothing is sent.
3. Toggle Hide name / Hide email / Hide password / Hide subject independently. Verify the preview
   and a real posting form. Previously typed draft text must not be erased.
4. Check a real form with captcha and attachments in portrait and landscape:
   the send panel stays accessible, overflow text scrolls, and the caret remains visible.
5. Check deletion-password and user-icon support on a forum that provides them.
   The password field is hidden by default. Hiding it must not disable deletion-password
   handling or replace a custom password entered before hiding the field. Forums may require
   a subject for new threads; re-enable the field if required by that forum.
6. Search settings for editor height, subject and preview; results open the new screen.

## Borderless fields in the experimental interface

The real form and its preview use the same explicit `postingField` appearance.
Other input dialogs keep their existing appearance. With the new interface
enabled, form fields have a rounded filled background without normal/focus
outlines, plus an 8 dp bottom gap (larger existing margins are retained).
GONE fields contribute neither their height nor their margin. Colors derive
from the selected theme: enabled fields are lighter than the local form backdrop
in light and dark themes, including focus/error states. Nearly white themes get
a gently darker local backdrop, not a changed application window or global theme.
The previous contrast helper that could darken fields is no longer used for
their fill. Text/hints retain their readability checks in both focus states.
Focus uses a slight theme-color tint and the existing themed cursor. Validation
keeps its existing logic, with error tint/hint coloring instead of a red frame.

Disabling the experimental interface restores the native field background,
padding, original bottom margin and the original local form background.
F-Droid's `postingField` and `postingForm` are no-ops.
Text size, selection, editor height modes and other layout parameters are not
replaced. The existing sizing path already measures the added visible margins.
Prepared instrumentation fixtures cover color contrast and restoring spacing
without rolling back the latest editor bounds or draft. They have not been run.

On a device, check subject/comment spacing, visible name/email/password fields,
the captcha input, and the same fields in Preview. Try light, dark and custom
themes, focus each field, open/close the keyboard, and disable the new interface.
The form must keep its selected height mode and saved text.

No compilation, Gradle, lint, test execution or device checks were run for this handoff.
