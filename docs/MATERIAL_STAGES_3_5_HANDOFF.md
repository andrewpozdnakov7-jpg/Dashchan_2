# Material stages 3–5: source handoff

Date: 2026-10-21. Application version remains 3.2.65 / 11450.
Base commit: 92f608ab3299c14f9e159668319c7aad25050090 plus the intentional working-tree changes.
Comparison snapshot: Slooop-3.2.65-drawer-visual-polish-20261021-132930-source.zip.

## Status and evidence

Source implementation is ready for external compilation and device acceptance.
The user confirmed compilation of stage 2, NOT this stages 3–5 snapshot.
No Gradle, Java/native compilation, lint, test execution, APK creation,
installation or device verification was performed for this handoff.
Prepared fixtures are not passed tests. Static checks are not compilation.
No release, version bump, dependency upgrade or independent security audit.

## Coverage

| Stage | Consumers and actual components | Source status | Remaining acceptance |
| --- | --- | --- | --- |
| 3 | Core PreferenceFragment rows, categories, switches/checks, summaries and headers | Connected to shared color/type/shape roles through InterfaceAppearance.settingsRow | Build; device spacing, enabled/dependency, font and theme checks |
| 3 | Edit/MultipleEdit/Seek preferences | Standard framework dialogs; existing localized hints also supply labels; existing values and callbacks retained | Keyboard, validation, save/cancel and recreation |
| 4 | Standard dialogs in settings, download/archive/board flows | Explicit MotionDialogBuilder.standard opt-in; framework AlertDialog, native buttons and choice list with delegating adapter | OEM layout, callback/checked-state/IME acceptance |
| 4 | DialogMenu and main-toolbar overflow | RecyclerView menu and opt-in native popup overlay; disabled/checked colors, responsive width | Anchor, scroll, long labels, check states |
| 5 | Main navigation toolbar and CustomSearchView | Existing native Toolbar/SearchView; scoped surface/type, icons and search capsule | Navigation, query/selection/focus, clear/back/recreation |
| 5 | History, user boards, boards, local archives | Existing adapters with service-row role; responsive metadata container | Real list data, selection, date wrapping, unchanged actions |
| 5 | Loading/empty/error on the selected service screens | Existing ProgressBar hosting the existing Material LoadingIndicator drawable; scoped state styling, scrollable error at large fonts | Loading completion, empty/error/retry, narrow/large-font layout |

## Dimensions from the task

- Settings: minimum 56dp, or 64dp with a nonempty summary; horizontal 16dp,
  inner vertical 8dp, title/summary gap 2dp, widget separation 12dp.
  Title 16sp/24sp normal; summary 14sp/20sp normal. Section 14sp/20sp medium,
  padding 16dp above and 8dp below. Long text grows, not a fixed row height.
- Dialog: 28dp corners, 24dp content padding, title 22sp/28sp, body 14sp/20sp.
  Width capped at 560dp and available window minus 48dp; very narrow windows
  use 16dp outer margins. Native buttons use 14sp medium, target at least 48dp,
  capsule feedback and wrap/stack when the validated native button bar permits.
- Menus: 12dp corners, row minimum 48dp, text 14sp/20sp, horizontal 16dp.
- Main toolbar: minimum 56dp, title 20sp/24sp medium, subtitle 12sp/16sp.
  Existing user title customization remains authoritative where configured.
  Search: 48dp target, 40dp visible capsule area, text 16sp/24sp.
- Service rows: minimum 48dp, or 56dp with metadata; title 14sp/20sp,
  metadata 12sp/16sp. Trailing metadata stacks below on insufficient real width.

The standard-interface palette resolves the actual theme window background;
dark/custom/AMOLED are not forced to a light surface. Existing shared stage-2
color resolution, typography and shapes are reused, not replaced by a global
Material theme. F-Droid has the corresponding no-op facade and no new Material
imports in the shared adapters.

## Integration and preservation

Main implementation: distribution/github/src/com/mishiranu/dashchan/widget/
StandardAppearance.java and InterfaceAppearance.java; shared AppearanceColors,
AppearanceTypography, AppearanceShapes; paired F-Droid InterfaceAppearance.
Resource overlays are opt-in, not a global Theme.Material3 parent.

Settings binders: ui/preference/core/PreferenceFragment, DialogPreference,
EditPreference, MultipleEditPreference and SeekPreference.
Dialog/menu entry points: widget/MotionDialogBuilder, ui/DialogMenu and the
explicit .standard callers in settings, DownloadDialog, LocalArchives and Boards.
Toolbar/search: widget/ViewFactory, CustomSearchView, ThemeEngine and MainActivity.
Service adapters: HistoryAdapter, BoardsAdapter, UserBoardsAdapter, LocalArchives;
PageFragment opts in only history/boards/user-boards, not post/catalog rendering.

Layout and typography snapshots support repeated application and gate restoration.
Parent-owned geometry is refreshed after child typography restoration. Search
styling does not set the query, replace listeners, request focus or reset the
EditText transformation. Dialog styling uses attach/pre-draw, not replacement
of callers' OnShow/OnCancel/OnDismiss/validation handlers. Choice wrapping keeps
IDs, enabled states and checked positions; callback behavior needs device testing.

Native implementation details intentionally remain platform-owned: contextual
selection/action mode, popup anchoring/submenus and OEM dialog internals.
Unknown native button-bar structures retain native placement with multiline
buttons; no private IDs/reflection/reparenting hacks were added. Some structural
choices (custom dialog title, optional field labels, scrollable error host) are
made on screen creation: close/reopen the screen after switching the interface.
Do not interpret an already-open dialog as a live structural gate-switch test.

Specialized posting, captcha, editor, lock and player dialogs are not converted.
Gallery ListUnit/PagerUnit explicitly keep specialized DialogMenu styling; those
two gallery changes only pass the opt-out flag. Existing player/posting/security
logic and persistence are not changed by stages 3–5. Prior drawer polish is kept.
The separate drawer follow-up changes PAGES_TOGGLE to existing normal MENU role:
14sp, selected user font family, same color/geometry and number formatting,
normal in both collapsed and expanded states; section headings stay medium.
Stage 6 (posts/catalog) is not implemented by this task.

## Prepared tests — NOT executed

distribution/github/instrumentationTests/.../widget/StandardAppearanceTest.java:
seven fixtures for long summaries at 1/1.3/2 font scale and RTL; repeat/restore
and highlight spans; check preference dispatch/dependency; native search query
and selection retention; service rebind; actual-window/AMOLED contrast; metadata
stacking. The metadata integration fixture requires the new-interface gate on.
StandardDialogLifecycleTest.java adds two fixtures using the existing disposable
debug Activity: custom validation/cancel dispatch and multichoice preservation.
No real user files or destructive operations are used by these new fixtures.

## Device checklist

1. Enable new interface. Open settings root and deep categories; check switches,
   summaries, section spacing, disabled dependent settings and long titles.
2. Open text, multiple-field and slider preferences: keyboard, editing, confirm,
   cancel and invalid input. Confirm a canceled change was not saved.
3. Open normal confirmation, radio/multiple-choice and overflow/context menus.
   Check long labels, current selection, disabled items and single action dispatch.
   Do not delete real archives or messages for this check.
4. Search settings and use the navigation toolbar search: Cyrillic input,
   selection/cursor, clear, back, rotation and reopening; no lost query/double action.
5. Open history, boards/my boards and local archives with actual content. Check
   scrolling, grouping, metadata/dates, selection and original row actions.
6. Exercise a naturally available empty/loading/error screen and retry. No stale
   or duplicate loader, repeated request, clipped text or inaccessible action.
7. Repeat representative screens in light/dark/custom/AMOLED, landscape and
   enlarged Android font/display scale. Compare on the same device/theme/data.
8. Disable the new interface, close/reopen screens and check legacy appearance;
   re-enable and verify that spacing does not accumulate. Check animations off.
9. Drawer: expand/collapse “Показать остальные”; normal font survives scrolling,
   theme/font changes and reopening. Headings remain medium; eye/number actions
   and previously accepted geometry remain intact.
10. Smoke-test protected posting/captcha/player/editor flows for unintended visual
    changes. Their specialized geometry was not an intended target of this task.

## Static checks completed

Git whitespace check with CR-at-EOL handling; both new resource XML files parse;
GitHub/F-Droid facade public static signatures match; source comparison against
the prior verified ZIP identifies scoped stage 3–5 consumers plus the drawer
font follow-up. Frozen ZIP layout, content and wrapper checks are recorded in
the external local checkpoint after packaging, not claimed as APK verification.

## Follow-up: native toolbar popup alignment (after the initial ZIP)

The user reported low-contrast rectangular overflow surfaces and labels positioned
above their checkboxes/submenu arrows. These follow-up source changes are NOT in
Slooop-3.2.65-material-stages3-5-20261021-142611-source.zip.

- Restored the existing isPopup marker in Theme.Standard.Popup so the ThemeEngine
  attach hook and SurfaceMotion policy are registered for that context.
- Motion policy reapplies the explicit standard overlay after its generic overlay,
  preserving standard typography while keeping animated/static transition choice.
- Introduced scoped POPUP_TEXT role: no forced multiline or 48dp minimum on the
  native text child. Framework rows continue owning alignment and touch geometry;
  no private dropdownListPreferredItemHeight attribute or internal IDs are used.
- Surface, label, native checkbox/radio and arrow tints use one resolved palette;
  the rounded popup background clips to its outline.
- Added two regression fixture sources for policy ordering/markers and native-like
  label/control alignment, font scale, callback preservation and label contrast.
  They have NOT been compiled or run. OEM popup/device acceptance remains pending.
- No ZIP, APK, Gradle/test execution, public action or specialized-menu redesign.

## Follow-up: main status bar matches the standard toolbar

The main ExpandedScreen inset foreground now obtains the same resolved surface
as the standard toolbar through paired InterfaceAppearance.standardStatusBarSurface
facades (null in legacy/F-Droid). This changes the actual pixels behind the
transparent system bar, not a deprecated window-color-only workaround.

Status icon appearance is selected by black/white contrast against the resolved
visible surface, including chrome alpha, the existing action-mode guard and the
drawer foreground once the drawer is at least half open. Only the public
APPEARANCE_LIGHT_STATUS_BARS mask is written; navigation icon flags/visibility,
insets, geometry, old alpha timings and drawer painting remain unchanged.
Initial legacy status icon appearance is captured on taking ownership and restored
on gate-off. Main-window focus return refreshes the policy; separate gallery,
video/PiP, editor and lock windows are not restyled by this change.

Sources: shared widget/ExpandedScreen.java, ui/MainActivity.java and the two
distribution InterfaceAppearance facades. Four fixture sources in
instrumentationTests/.../widget/ExpandedScreenStatusBarTest.java cover exact
surface color, contrast, alpha/guard, drawer and masked appearance flags.
They are NOT compiled/run. No device acceptance or APK build is claimed.

Phone checks for the two follow-ups:

1. New UI, light theme: status-bar background matches the toolbar; clock/battery
   and other system icons are dark and readable. Open history and nested settings.
2. Dark/AMOLED/custom theme: background follows that theme, icons remain readable;
   change theme, rotate and background/foreground the application.
3. Open/close the drawer, start/end selection mode, hide/show the toolbar by
   scrolling. Return from gallery/video; no stale blue strip or wrong icon tone.
4. Open the overflow in history and a thread; compare label/checkbox/arrow vertical
   centers, text contrast, rounded surface and enter/exit motion. Reopen submenus.
5. Disable new UI and reopen screens: old toolbar/status bar/menu policy returns.
   Check with system animations disabled too. No screenshot/IME/user-data policy
   changes are intended. Destructive actions must use disposable data only.

## Follow-up: choice-dialog and submenu spacing (screenshots 05/06)

- Own title padding no longer stacks with decorative native title-divider
  spacers or native parent padding. Only the direct vertical parent of our own
  title and noninteractive Space/plain-View spacers are adjusted, without private
  IDs. Native input/title contents are not removed.
- Choice-list vertical padding is coordinated with the title and button bar.
  Saved geometry is restored on gate-off. Font sizes remain unchanged.
- A validated native button bar has explicit padding. Its 12dp end padding plus
  the button's 12dp text inset align the action caption to the content's 24dp edge.
  Button padding is assigned after the ripple background; optional one-child
  scroll-wrapper padding is cleared and saved for restoration. Handlers untouched.
- CheckedTextView radio/check indicators now tint both compound drawable and
  check mark through the same checked/normal/disabled accent palette as popup
  controls. The initial tints are included in layout snapshots for restoration.
- Standard popup background is supplied through the popup widget style BEFORE
  measurement: symmetric 8dp outer insets, 8dp internal vertical padding, radius
  12dp. The palette-adjusted drawable has the same padding, so attach-time tinting
  does not change the geometry used for placement. Native dropdown-list vertical
  padding is zeroed in the opt-in style to avoid another added padding owner.
- Animated and static standard widget styles retain their respective existing
  transitions. Native anchoring, menu entries, selection and callbacks retained.
- Two new fixture sources cover premeasurement-vs-runtime drawable padding,
  choice-list spacing, cancel caption inset, selected tint and retained selection.
  Existing motion-policy expected styles were updated. Tests NOT compiled/run.
- Device acceptance: repeat 05/06 in light/dark themes, select/cancel/reopen,
  check submenu edge gaps and top/bottom space, long text, rotation and large fonts.
  Also test animations disabled and legacy mode. No APK/device success claimed.

## Follow-up: dialog opening geometry (115/22 video)

The cache-size dialog visibly moved right/down during opening. Source geometry
was previously applied in pre-draw after WindowManager had received the initial
window, permitting a second layout during the native enter animation.

- Standard dialog width and background are now prepared at registration, before
  show/addView. Content styling runs on decor attach, before initial measurement,
  not in pre-draw. Bounds/background writes are skipped when already correct.
- This deliberately does NOT eagerly inflate AlertDialog via dialog.create():
  late caller setup of native buttons/views and caller OnShow validation remain
  supported. OnShow/OnCancel/OnDismiss handlers are not replaced.
- DialogMenu supplies its own view/minimum width before show through paired
  prepareStandardMenuDialog facades, preserving its menu width and 12dp radius.
- ThemeEngine's native child-attach pass skips background replacement only when
  an enabled standard dialog already owns that surface; overlay focus dispatch
  and all specialized/legacy dialog paths remain intact.
- Window snapshots are captured before preparation, retaining gate-off restore.
  No compensating translation, extra animation, timing or typography changes.
- Two new lifecycle fixture sources check stable pre-show/first-traversal bounds
  and drawable identity, late neutral-button setup and single OnShow dispatch.
  They are NOT compiled/run. Repeated-opening device acceptance is pending.
- Favorites board grouping default is confirmed false; saved explicit choices
  are not reset. No settings migration is introduced.

Phone checks: repeatedly open/cancel Cache size; compare input, radio-choice,
confirmation and DialogMenu windows. Watch the whole surface and controls during
entry, not just the ending frame. Test confirm/cancel/neutral actions, keyboard,
rotation, large font, dark theme, animations off and legacy UI. Existing 05/06
spacing, popup and main status-bar fixes are retained in the source snapshot.
