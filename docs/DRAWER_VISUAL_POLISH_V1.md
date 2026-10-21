# Drawer visual polish V1 — source handoff

Implements the 2026-10-21 SLOOOP_DRAWER_VISUAL_POLISH_V1 specification on top of
Material stage 2, source version 3.2.65 / 11450, base commit
92f608ab3299c14f9e159668319c7aad25050090. No version change or publication.

## Explicit dimension overrides

These values supersede the earlier stage-2 preservation table for the drawer
only. They are taken from the new task, not measured from the concept image.

| Property | Implemented rule | Source/consumer |
|---|---|---|
| Counter width | max(64dp, 32dp + ceil(widest measured sample)) | DrawerStyle; samples include original abbreviations, zero and int extremes |
| Counter visible height | max(28dp, ceil(font bottom-top) + 6dp) | DrawerStyle; the actual selected 12sp medium font |
| Counter radius | half actual visible height | WatcherChipView background and WatcherView ripple mask |
| Counter internal layout | 6dp start/end, 16dp icon/loader, 4dp before number | DrawerStyle constants; paint, loader, snapshot clip and click boundary |
| Row | min 36dp, grows for chip and actual title/body metrics | DrawerStyle and existing DrawerItemLayout narrow/large-font fallback |
| Text to chip / chip to panel edge | 8dp / 8dp, each once | Existing text end padding / watcher row end padding; no extra action margin |
| Menu icons / outline | 22dp inside unchanged 24dp slot / 1.6 viewport units | DrawerGlyph profile passed only by drawer consumers; filled eye/overflow unchanged |
| Menu icon-to-label layout gap | 24dp instead of 32dp | Existing icon end-margin source at bind; other row types retain 32dp |
| Group arrow-to-text | unchanged 16dp | DrawerForm |
| Heading-to-count | measured 6dp | DrawerCountSpan replacement span; no layout made from repeated spaces |

Runtime chipWidth/chipHeight are intentionally NOT claimed here: no Android font
measurement/build/device execution was performed. The expected 64–72dp width at
ordinary scale is a task guideline, not a cap. Custom fonts and larger fontScale
may require more space. No percentages, text scaleX or per-update resizing.

## Typography and counts

- Section/page headings: 14sp selected application medium.
- Thread/menu labels and board groups: 14sp selected application normal;
  group text uses variant. All retain native line spacing, no extra tracking.
- Watcher and inline heading counts: 12sp selected application medium, tnum.
- FontManager's explicit resolver now derives both 400/500 weights from the
  selected family when present, instead of using the system medium for custom
  families. No global application font setting is changed.
- Only visual section/group titles are reformatted, leaving controller titles,
  IDs and data untouched. Counts come from the existing filtered favorites
  snapshot, including collapsed children. Inline span has no extra click/focus
  target; headings expose complete localized plural content descriptions.

## Color and interaction boundaries

Only the fixed light Normie/Neutron active-zero pair changes to #E3EDFC/#285887.
Unread #C9DEFF/#123F7A and neutral #E3E7EF/#394454 remain; dark/custom/dynamic
pairs and the AMOLED black base are unchanged. Safe stage-2 opaque arithmetic
and translucent ripple are retained. No new dividers, shadows or textured fills.

Existing checked/status priority and counter formatting are unchanged. Eye
toggle and number refresh remain separate, now using the same 26dp logical
number-start boundary as painting. Full-row WatcherView owns the accessible
actions and hitbox; the 28dp capsule does not shrink the row's target.
Selection, long press, drag, order, storage, migration and polling are untouched.
The previous DrawerForm method-reference compilation fix is retained.

Stage-2 crossfade retarget snapshots, bounds and release rules are preserved.
Loader remains live outside snapshots; 200ms color / 120ms content / 150ms loader
timings and reduced-motion behavior remain. No new animation is introduced.

Legacy/F-Droid gates and the public InterfaceAppearance API are unchanged.
Posting, captcha, attachments, player, security/lock and photo-editor geometry
are not modified. Drawer width and its user-configurable external radius stay.

## Prepared checks, not executed

- DrawerMaterialGeometryTest: new end edge, normal/medium roles, original narrow
  selection/RTL fallback plus scale 1.3/2, metric-based capsule/sample bounds and
  exact heading count-gap semantics in both directions.
- AppearanceTokensTest: updated only the two approved fixed-light color
  expectations; added their contrast threshold. Other palette fixtures retained.
- WatcherChipRetargetTest: loader-exclusion bound follows the shared profile.
  Existing interrupted fade/action/recycle fixtures remain regression targets.

Static diff/whitespace, resource XML and source integration reviews are distinct
from test execution. No Gradle, javac, lint, automated tests, APK creation,
installation or device visual acceptance were run for this handoff.

## Device checklist

1. Compare the same theme/font/data/scroll position before and after: narrower
   capsule, lighter label weights, compact rows and menu gaps, no added sections.
2. Check 0/+1/+999/999K+/999M+/negative/extreme counts, enabled/disabled,
   checking/error/unavailable/deleted; widths stay stable and digits fit.
3. Eye toggles, number refreshes; selection, long-press, group collapse and drag
   remain correct. Loader and ripple align with the capsule in LTR and RTL.
4. Try fontScale 1/1.3/2, custom font, narrow window, light/dark/custom/dynamic
   and AMOLED, on/off/on and recreation; no overlap or stale geometry.
5. Rapid counts, repeated toggles, recycle/background, system animations off:
   no previous-target flash, old thread snapshot or stuck loader.
6. Headings retain correct counts when collapsed; TalkBack reads full localized
   title/count, while the three-dot actions remain a menu (not a tracking eye).

References: Android [ReplacementSpan](https://developer.android.com/reference/android/text/style/ReplacementSpan)
and [ImageView.ScaleType](https://developer.android.com/reference/android/widget/ImageView.ScaleType).
