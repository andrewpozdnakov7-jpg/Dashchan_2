# Drawer / favorites Material 3-inspired presentation

Latest values: [Drawer visual polish V1](DRAWER_VISUAL_POLISH_V1.md). Its explicit
capsule, typography, heading counts and menu gaps supersede older values below.
The number-action boundary now follows the shared logical number-start slot.

Source-only implementation of the Favorites Material 3 V1 design brief.
The subsequent user-approved compact-size override below supersedes the V1
geometry and typography, not its colors, icons, animations or behavior.
The feature uses the existing new-interface switch; there is no new setting.
When it is off, the old drawer views, typography and counter renderer remain.
Changing that switch recreates the Activity through its existing saved-state
path so all captured surfaces, outline backgrounds and status-bar pixels are
rebuilt together. Drawer width, gestures, scrim and drawer-motion algorithms
are unchanged.

## Implementation map

- `widget/DrawerStyle.java`: local colors, text roles and font-aware geometry.
  Reference light/dark colors for built-in Normie/Neutron; custom/dynamic themes
  retain their surface/accent, with contrast-checked foregrounds. AMOLED uses
  black. Selected font family and system font scaling use the existing helpers.
- `graphics/DrawerGlyph.java`: original code-drawn outline navigation/status
  icons. No screenshots, imported artwork, fonts or additional dependencies.
- `widget/DrawerItemLayout.java`: one adapter item with a fixed trailing
  watcher button, legacy 8dp title gap and 16dp/4dp outer padding; a large-font/narrow
  layout can place the action below the title instead of overlaying it.
- `widget/WatcherPresentation.java`: pure normalization and compact number
  formatting, including negative values and integer extremes. Disabled masks
  shared reply-tracking count/running/error. Deleted wins, then error, paused,
  checking, and ordinary enabled state. There is no UNKNOWN state.
- `widget/WatcherChipView.java` and `WatcherView.java`: eye/status + count,
  separate clipped ripple, checkable accessibility node and localized exact
  counts. Color transitions 200ms, icon/count crossfades 120ms, loader delayed
  150ms. A static sync icon replaces spinning when animation is not permitted.
  First bind and binding another thread do not animate. Recycle/detach cancel
  visual work; callbacks still resolve the current stable-ID adapter row.
- `ui/DrawerForm.java`: per-row text roles, arrows, menu and navigation glyphs,
  surfaces and custom divider; existing watcher toggle/menu handlers retained.
  Watcher UI updates are deferred during scroll/drag/multi-selection; visual
  work is disabled while paused or the drawer is hidden. No second item animator.
- `ui/MainActivity.java`: matching common/wide/status-area background at
  creation, lifecycle animation gate and refresh on interface-style changes.
- `widget/DividerItemDecoration.java`: optional local drawable; other users of
  the decoration retain the existing themed divider.
- `res/values/drawer_material.xml`, `res/values-ru/drawer_material.xml`:
  localized action/status/accessibility strings.

No new polling, notification behavior, favorite storage changes or new order
model are introduced by the visual layer. A later user-requested number tap
can explicitly queue a single-thread check through the existing watcher worker.
The preceding user-
requested default-on watcher migration and multiline rename remain intact.
The live watcher path reads counts from PagesDatabase MARK_NEW counts and
ReadPostsTask insertion results; negative formatting is defensive compatibility,
not a newly inferred unread state or a change to the underlying model.

## User-approved compact sizing override

After reviewing device screenshots, the user requested legacy sizes and spacing
with the new design retained. This supersedes the original V1 size tokens:

- Rows and the pages toggle: normally 36dp, not 52/56dp. Section/group text:
  minimum 32dp plus 2dp above/below, not a 48dp section minimum.
- Titles, board groups, thread names and navigation: 14sp, existing application
  medium typeface, native line spacing. No 16sp titles, 12sp board labels or
  20/24sp forced line heights. Counter text returns to the previous 12sp size.
- Legacy horizontal gaps return: icon-to-text 32dp, group arrow-to-text 16dp,
  title-to-trailing-action 8dp, trailing edge 4dp for actionable rows.
- New chip colors, eye/status icons, rounded background, minimum 80dp chip
  width and 32dp visible chip height remain. Compact rows have 2dp above/below
  that background instead of inflating every row to a 48/52dp touch target.
- Large fonts still grow rows and can wrap the trailing action below the title;
  font scaling is not clamped. Colors, animation timing, accessibility state,
  favorites order/groups, watcher behavior and legacy-style gate are unchanged.

These are source changes, not device-verified dimensions. The prepared compact
geometry/typography regression fixture has not been executed.

## Prepared verification (not executed)

- `WatcherPresentationTest`: six JVM cases for masking, priority, unavailable,
  preserved counts, compact thresholds and integer extremes.
- `DrawerMaterialGeometryTest`: four detached instrumentation fixtures for
  row geometry, large-font narrow/RTL fallback, binding reset/accessibility and
  resolved foreground contrast. Does not start monitoring or alter preferences.
- Existing drawer content/grouping/appearance tests remain regression targets.

Only source/static checks were performed. No Gradle, tests, lint, compilation,
APK, device screenshots or runtime validation are claimed. No new ZIP created.

## Manual checklist

1. New interface on/off: verify a coherent switch of the entire drawer, no
   stale surface under rounded corners/status bar. Existing radii, wide mode,
   current screen, open groups and drawer order must remain correct.
2. Light/dark/AMOLED/custom accent and app font: check readable titles and
   consistent icon treatment. Try system fontScale 1/1.3/2 and narrow windows;
   titles/actions must not overlap or shrink the chosen font.
3. Watcher off/on/zero/new/error/paused/deleted: tap the eye/status slot to
   toggle, the number to check that thread without toggling, and the title to
   navigate. Number refresh requires enabled monitoring. Check the favorite
   actions menu and every collapse arrow.
4. Short/long checks: no spinner flash for checks below 150ms, number remains
   visible while spinning; close drawer, scroll, pause/resume, set animation
   scale to zero and rapidly toggle. No off-screen looping or wrong-thread data.
5. Grouping on/off, unread-first, merged forums, selection/delete, manual
   reorder, hidden deleted favorites; search, forum switcher, pages, combined
   feeds, Reddit actions and all navigation entries must retain their actions.
6. TalkBack: one checkable watcher node with thread name, current status and
   exact count; disabled must not reveal shared reply-tracking counts. No
   assertive announcements on background refresh. Compact action height follows
   the row (normally 36dp), as explicitly requested instead of V1's 48dp minimum.

The PNG concept is only a visual guide: top action remains more_vert, arrows
are retained, disabled shows 0 rather than an invented unknown count, flat
surfaces have no texture, and corner radius follows the existing preference.
Optional toggle Toast was not added. Existing actions are used unchanged.

Stage 2 now shares opaque color, palette, typography and shape roles with the
existing interface appearance adapter, and freezes interrupted chip alpha
compositions instead of restarting from a fully opaque previous target.
See `MATERIAL_STAGE2_HANDOFF.md` for the exact scope, compatibility policies,
buffer limits, prepared checks and device acceptance still outstanding.

## User-requested split actions

The compact chip keeps its current appearance. Its leading 32dp eye/status
region toggles monitoring; the remaining number region requests a check of
only that favorite. The regions mirror in RTL and retain the action selected
on touch-down for the gesture. The legacy renderer without an eye retains its
original whole-counter toggle. Mass selection disables both actions.

Manual number checks do not enable monitoring or reset unread counts. They
require monitoring enabled, and use WatcherService.Client.refreshThread plus
the existing sorted worker queue. Already running/queued, blocked and unsupported
threads are ignored, without a cancel/restart loop. Explicit checks use the
same network policy as the existing user-triggered refresh-all command.

TalkBack retains one checkable node with its normal toggle action and an
additional localized "Check this thread" action when monitoring is enabled.
Prepared WatcherChipActionsTest has three detached routing fixtures for LTR/RTL,
same-ID rebind, disabled/selection gating, cancellation and accessibility action.
These tests have not been run; no network or service execution is claimed.

References consulted: [Android Views accessibility](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views)
and [ValueAnimator animation policy](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled()).
