# Posting attachment viewport

The experimental posting form in AUTOMATIC editor-height mode now limits the
height deducted for attachments to 20% of the attachment-free editor height
when the keyboard is hidden. When the keyboard is visible, attachments deduct
no height at all: the editor uses the same height as an attachment-free form
in that keyboard-reduced viewport. This also applies to floating keyboards
which may be visible without reducing the viewport.
The rest of the attachment list extends the existing outer ScrollView; it does
not introduce another nested list. Non-attachment fields still participate in
the compact-footer fallback for small viewports, keyboards and large fonts.

COMPACT, EXPANDED and FIXED modes, the original form and the F-Droid form retain
their existing sizing policy. Sending, captcha, draft and attachment ownership
are unchanged. Geometry diagnostics contain heights, not attachment contents.

## Prepared coverage (not executed)

`EditorHeightPolicyTest` covers no attachments, small attachments, the 20% cap,
multiple large rows, removal, rounding, short viewports and invalid geometry.
It also covers attachment-count independence with a visible keyboard and
switching between the keyboard-visible and keyboard-hidden policies.
No compilation, Gradle tasks, automated tests or device checks were run for this
source-only handoff. The fixed-footer integration still needs device validation.

## Manual checks

1. Enable the experimental posting form and AUTOMATIC editor height. Record the
   empty form's comment height with the keyboard hidden.
2. Add one, two and the maximum supported number of attachments. The comment
   should retain at least 80% of its attachment-free height in the same viewport.
   Swipe up in the outer form to reveal the attachment rows below it; swipe down
   to return to the comment. Do not confuse this with scrolling inside long text.
3. Remove all attachments: the comment should regain its original height. Check
   attachment preview, reorder, edit and removal; no row or file should be lost.
4. Keep a long comment and a selection, open/close the keyboard, rotate, and try
   larger system fonts. Text, caret and drafts must survive. Captcha/send must
   remain reachable, including the compact fallback on short screens.
   With the keyboard open, compare zero, one and multiple attachments: comment
   height must be identical in the same viewport. The attachment list remains
   below it and can be revealed by scrolling the outer form. Close the keyboard
   and verify the 20% cap is restored. Also check adding/removing attachments
   while the keyboard stays open, and a floating keyboard if available.
5. Check each explicit height mode and the original form for unchanged behavior.
   Check the offline form preview and ordinary submission/captcha flows.

The same source bundle also includes the preceding captcha confirmation timing
change: 200 ms check drawing, 150 ms hold and 200 ms transition to Send (550 ms
in sequence; the initial content fade and color change run concurrently).
