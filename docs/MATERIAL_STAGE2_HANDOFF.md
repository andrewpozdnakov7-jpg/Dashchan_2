# Material appearance foundation — stage 2

Later drawer-only override: [visual polish V1](DRAWER_VISUAL_POLISH_V1.md).
Its explicit 64/28dp capsule, typography and menu gaps supersede this document's
original stage-2 preservation values for those properties; other profiles remain.

Source implementation of SLOOOP_MATERIAL_STAGE2_V1 (2026-10-21). Base commit:
`92f608ab3299c14f9e159668319c7aad25050090`, source version 3.2.65 / 11450.
This is not a release/version bump. Earlier Favorites, split chip actions and
continuous-background authentication interval changes remain in this checkout.

## Stage 1 fixes included

- Drawer-derived containers and foregrounds are opaque after blending. Genuine
  translucent theme/card/accent inputs are composited over their actual backing
  before use. Normie/Neutron reference RGB values remain unchanged; AMOLED keeps
  a black base. Ripple/scrim are not converted to opaque colors.
- Interrupted watcher content fades capture the currently visible alpha mixture,
  not the complete previous target. Capture uses up to two reusable software
  buffers of the chip's bounds, excluding the background and live child loader.
  Capture happens only on retarget, never per animation frame. At most 512,000
  pixels per buffer (two ARGB buffers: at most 4,096,000 pixel-storage bytes).
  Above that bound or before layout, rendering snaps to the latest state.
- A capture reads the current source buffer into a distinct scratch buffer,
  then swaps them. Repeated interruption therefore preserves the current
  composition without accumulating layers. Frozen pixels fade out while the
  latest target fades in. Buffers are released at the end of the content fade,
  snap, cancellation, recycle/detach, size or RTL change. They are not explicitly
  recycled while hardware display lists might still reference them.
- Color continues from its current values for 200ms; content crossfade remains
  120ms (linear alpha), loader delay 150ms. Loader gating, stable IDs, masking,
  status priority and the newer eye-toggle/number-refresh actions are retained.

## Shared roles and actual consumers

| Layer | Real use | Compatibility choice |
|---|---|---|
| `util/OpaqueColors` | `AppearanceColors`, GitHub `InterfaceAppearance`, `PlayerButtonsColors`, watcher color transition | Drawer direct fallback; interface 20 steps/black-on-tie; player 100 steps/white-on-tie |
| `widget/AppearanceColors` | `DrawerStyle` and GitHub `InterfaceAppearance.Palette` | Named `DRAWER_COMPACT` and `INTERFACE_COMPATIBILITY` recipes, not one imposed palette |
| `widget/AppearanceTypography` | Drawer labels/counter and existing interface action/audio roles | Drawer 14/12sp medium + native lines; compatibility buttons 14sp/audio 18sp preserve their existing font/line metrics |
| `content/FontManager.resolveTypeface` | Common typography resolver/configuration key | Uses the existing selected-font cache and existing medium fallback |
| `widget/AppearanceShapes` | Interface rows/actions/fields and watcher chip/mask | Existing 12/28dp corners and field strokes; no external drawer-radius change |

Semantic colors include window/surface/surfaceContainer, text/secondary,
primary and tonal pairs, outline/decorative outline, error text/icon/container
pairs, disabled roles and a separately translucent ripple. Drawer unread colors
are a profile extension. Error text has a 4.5:1 role, while existing decorative
interface error strokes keep the previous 3:1 recipe. Disabled colors are not
claimed to meet enabled-text thresholds.

The seven larger M3 typography roles are explicit opt-in API for later stages,
not an automatic traversal of all TextViews. Full typography snapshots can
restore their modified properties; compatibility roles restore only properties
they actually change, preserving externally updated fonts/editor behavior.

## Dimension provenance and preservation

The duplicate specification supplied in folder 115/19 is byte-identical to the
115/18 stage-2 package. It does not authorize a second redesign or another scale.
The following values come from TOKENS.md and the approved compact implementation,
not estimated proportions from an older concept image:

| Consumer | Preserved value | Source of the value |
|---|---|---|
| Drawer row / pages toggle | minimum 36dp | Compact profile in TOKENS.md; DrawerStyle / DrawerForm |
| Drawer labels / counters | 14sp medium / 12sp medium; native lines | Compact profile; AppearanceTypography DRAWER roles |
| Counter chip | minimum 80x32dp, radius 12dp | Compact profile; DrawerStyle / WatcherView |
| Drawer section label | minimum 32dp plus 2dp top and bottom | Compact profile; DrawerForm |
| Icon-to-text / group-arrow-to-text | existing 32dp / 16dp gaps | Compact profile; DrawerForm |
| Text-to-action / actionable trailing edge | existing 8dp / 4dp | Compact profile; DrawerForm |
| Interface action | 14sp, minimum 48dp, padding 16dp horizontal / 8dp vertical | Existing InterfaceAppearance values, transferred without numeric change |
| Interface audio title | 18sp, up to two lines | Existing InterfaceAppearance values |
| Interface row / field / chip corners | 12dp | Existing local values; AppearanceShapes.MEDIUM |
| Interface action corners / field strokes | 28dp / 1dp normal and 2dp focused | Existing local values; explicit compatibility tokens |

Minimum dimensions remain adaptive to measured text, large fonts and narrow/RTL
layouts. They are not fixed clipping bounds. Existing local values are not rounded
to the nearest shared token. The new 22/16/14/12sp M3 role scale is opt-in for
future consumers, not automatically assigned to existing screens. Posting, captcha,
attachments, video controls, editor and lock-screen geometry is not migrated here.

## Refresh, gate and restoration

No Context-keyed palette cache remains in InterfaceAppearance. Each resolution
reads current ThemeEngine colors and Android attributes; the idempotence guard
compares immutable color values plus font identity, fontScale and density,
instead of only Theme object identity. Unchanged roles retain their drawables.

Existing theme/font update paths recreate MainActivity: theme selection,
dynamic-theme refresh, AccessibilityFragment font change, font catalog selection
and installation. DrawerStyle is rebuilt with the drawer in that existing path.
The new-interface gate is unchanged and has no additional Android-14 constraint.
F-Droid InterfaceAppearance remains the same no-op API; the common foundation
has no Material imports/dependencies and is not itself an activation mechanism.

## Explicit non-migrations

- No global Theme.Material3 parent, Compose conversion, dependency/version change
  or release metadata change.
- No new views, cards, dividers, section labels or padding on existing screens.
- Posting, captcha, attachments, video overlays, player-editor layout/drag logic,
  lock/cat and photo editor retain their local appearance. PlayerButtonsColors
  delegates only its arithmetic, with its 100-step results preserved.
- Existing fallback posting light-fill calculation remains local; its opaque
  arithmetic delegates to the helper. Intentionally translucent text-action
  state layers still use translucent blending, not opaqueBlend.
- Compact drawer remains 36dp rows, 14sp medium labels, 12sp counters, native
  spacing, user-approved gaps and adaptive large-font/RTL fallback.
- No changes to storage, watcher defaults/migrations, grouping/order/unread,
  selection/reorder/rename or split-action handling in this stage.

## Prepared regression checks — not executed

### External compilation report follow-up

The reported Java error in DrawerForm's number-refresh listener is corrected:
the field initializer now stores a method reference, and the unchanged handler
body reads the constructor-initialized final WatcherService.Client only when
the click is dispatched. The field remains final; selection and thread guards,
refresh behavior and UI geometry are unchanged. No visual-polish V1 changes
are part of this fix. Compilation has not been rerun locally.

- Shared `AppearanceTokensTest`: opaque blends and policy contrast, exact drawer
  reference palette, custom/dynamic/AMOLED/transparent inputs, typography
  idempotence/restoration and configuration keys (4 fixtures).
- Shared `WatcherChipRetargetTest`: repeated software-Canvas mixture captures,
  0/1/2/999/1000 transitions, bounded/released buffers and loader exclusion
  (2 deterministic fixtures; not real hardware animation acceptance).
- GitHub `InterfaceAppearanceTest`: added repeat/on/off restoration and Android
  attribute changes with the same ThemeEngine Theme object (2 fixtures).
- GitHub `PlayerButtonsColorCompatibilityTest`: exact comparison against the
  pre-refactor 100-step player policy and blend recipes (1 matrix fixture).
- Existing drawer geometry, watcher actions, theme/appearance and posting
  fixtures remain regression targets; no new test framework introduced.

Static source/diff review is distinct from tests. No Gradle, compilation, lint,
APK builds for either distribution, instrumentation execution, installation,
hardware rendering, screenshots or device acceptance were performed here.

## Device checklist for the source ZIP

1. New interface on/off/on: existing screens retain dimensions, listeners,
   input selection and state; legacy off and F-Droid keep their old UI.
2. Compact drawer, Normie/Neutron/custom/dynamic/AMOLED and transparent accent:
   no contrast-alpha crash, readable text, unchanged spacing and reference RGB.
3. Rapid watcher changes/toggles and refresh: no intermediate-count flash;
   loader/error/unavailable/deleted remain correct. Eye toggles, number refreshes.
4. Close/reopen, scroll/select/reorder, rotate/recycle, background/return and
   system animations off: no stale snapshots, old thread content or stuck loader.
5. Theme and font changes, fontScale 1/1.3/2, narrow window and RTL: no stale
   palette/metrics, clipping or overlap. Player-button editor colors unchanged.
6. Posting/captcha/attachments/video/lock/photo editor: compare with the previous
   build on the same device/theme/font/data; no redesign is intended in stage 2.
7. The ZIP also includes the preceding timer fix: authenticate, read 10 minutes,
   leave for 2:30 with 3 minutes selected, return without proof; next departure
   starts another 3 minutes. Continuous absence of 3 minutes requires proof.

References: [AndroidX ColorUtils opaque/composite contract](https://developer.android.com/reference/androidx/core/graphics/ColorUtils),
[Android Canvas bitmap drawing](https://developer.android.com/reference/android/graphics/Canvas).
