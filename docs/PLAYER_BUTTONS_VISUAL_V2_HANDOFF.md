# Player button layout: visual V2

Presentation-only integration of the approved V2 concept. No dependency, persistence format,
player runtime, tap-toggle, long-press, swap/move, right-panel capacity, column choices or
animation timing changes in this visual patch.

- Short 14sp hint, 18sp section heading and 48dp help button; detailed RU/EN help is on demand.
- Theme-derived tonal surfaces, 20dp outer rounding, 16dp group rounding, 12dp cell rounding.
  Actual player action icons and muted hidden-button states are retained.
- Group labels are compact; the right count includes all placed buttons, including hidden ones.
- Plain column setting row, number chip, divider, quiet automatic-save note and text reset.
- Responsive 16/24dp page padding, existing 560dp cap and existing adaptive column/stack policy.
- Existing 48dp free-drop bands, stable status capacity and persistent six button Views remain.
  Painted group backgrounds now also cover labels/drop bands; hit zones still exclude labels.
- Motion captures the new painted surface rectangles, not the smaller cell-grid rectangles.
  Button paths, lift/hover/landing parameters, gesture routing and commit points are unchanged.
- Invalid-target highlight is drawn over the group background but under actual button cells,
  preserving visible rejection feedback and the priority of valid swap targets.
- Neutral toolbar appearance is local and restored when leaving. Existing measured toolbar
  title sizing, navigation callbacks, system insets and system bar policy are retained.

Not compiled, tested or visually validated on an Android device in this source-only handoff.
No APK is provided. Check light/dark themes, large fonts, landscape, 1-6 columns, tap toggles,
swaps and free-area moves, empty right panel, help, reset and toolbar restoration on device.

Follow-up crash fix: opaque surface interpolation explicitly restores alpha 255; float rounding
at a 0.055 blend must not feed alpha 254 to AndroidX calculateContrast. The contrast helper also
normalizes its opaque-background contract. Ripple/highlight transparency and motion are unchanged.
Regression fixtures include the reported FE-DADFE6 background and all used blend factors; not run.

Toolbar follow-up: install fresh temporary ColorDrawable objects rather than calling
setBackgroundColor on the saved originals. Leaving the editor restores the untouched theme
backgrounds, text/icon colors and elevations. An isolated two-visit fixture is prepared, not run.

The source bundle also retains the previously requested downloaded-update journal/reminders,
button-visibility toggles and process-independent, boot-bound reauthentication interval.
