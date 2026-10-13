# Experimental posting design V2

V2 form/option geometry remains; its formatting/captcha presentation is now superseded
by POSTING_DESIGN_V4.md (compact glyphs and centered Done, not a right-edge checkmark).

Supersedes V1 geometry. Source-only change: no compilation, tests, lint or device
screenshots were run/produced. Exact rendered correspondence is not yet confirmed.

## Layout

- Normal-font option rows: 32dp minimum, no gap, 16sp full labels; two rows are 64dp.
  Checkbox art remains 20dp, so the normal visual gap is 12dp. Text has its existing
  12dp gap from the box. The whole original checkbox row remains clickable; no extra
  container handler or overlapping touch delegate is installed.
- Both TextView and View minimum heights and the platform background are set locally
  to avoid an implicit 48dp minimum/inset. The original checkbox state/listeners,
  accessibility descriptions and unsupported-option visibility are preserved.
- Font scale above 1 uses at least 48dp per row. Labels wrap and grow without ellipsis,
  font shrinking or abbreviations. Unusual font metrics can also enlarge normal rows.
- Compact captcha is left of the option column, separated by 24dp, centered vertically.
  Width is min(168dp, safe window width - 232dp): 168dp at 400dp, 128dp at 360dp.
  The panel excludes the two existing 16dp outer margins. Height is at least 56dp.
- Below 104dp available captcha width, or if the actual labels do not fit beside it,
  captcha moves above the full-width option column. Its standalone width is up to
  168dp; the existing 12dp vertical fallback gap remains. RTL mirrors the columns.
- Subject/editor gap remains 12dp. Editor/options and options/send gaps are 16dp.
  No duplicate bottom padding is added. Measured footer height drives the same
  editor/scroll fallback and existing keyboard inset policy.

## Preserved behavior

Ready PASS/SKIP states still show the checkmark with its 6dp right inset in place of
the painted label; the label remains measured. Loading keeps the Material indicator.
Initial/loading/ready states share width and height. Reload and long-click delegate
to the existing captcha widget; real image/input challenges retain its full size.

Field hints, drafts, validation, sending, attachments, overflow menu and formatting
callbacks remain unchanged. Existing editor-height choices and distribution boundary
are retained. Previous video frame fixes are untouched.

## Prepared coverage, not executed

PostingDesignLayoutTest covers Russian full labels, compact two-column layout,
narrow and large-font stacking/non-overlap, RTL, hidden options and unchanged checked
state/listeners. PostingCompactControlsTest retains actual captcha transitions,
stable dimensions, checkmark inset, refresh delegation and single editor/footer gaps.

Device review: normal/narrow windows, keyboard/landscape, font scales 1.3/2, both themes,
captcha load/ready/reload/real challenge, checkbox toggles, draft and formatting actions.
