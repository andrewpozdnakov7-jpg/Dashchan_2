# Player button visibility

Source-only update. No Gradle, compilation, lint, automated tests or device
checks have been run for this patch.

## Behavior

- Short tap in the button layout editor toggles whether that action appears in
  the new player. The disabled action remains gray, enabled for interaction and
  draggable in the editor. It is not removed from its saved panel/order.
- Long press is consumed by the drag gesture, even if a drag cannot start; it
  must not fall through into a visibility toggle.
- The instruction above the editor explains both tap and long-press/drag.
- The player filters hidden actions before measuring its grids/bar: no empty
  cells. With no available left actions, the whole left capsule is GONE,
  including padding and margins. Play/pause, time and seek remain available.
- Changing columns, swapping/moving actions, rotation and process restart keep
  hidden states attached to button identity. Restore defaults enables all six
  actions and resets their original placement/columns.
- Old version-1 records enable every action and retain placement and columns.
  Version 2 adds a validated hidden-button list under the same preference key.
  When all actions are enabled again, the compact version-1 encoding is used.
- The old player is unchanged. F-Droid still has no new player/layout editor.

## Prepared coverage, not executed

- Pure Java model tests: legacy migration, toggle round-trip, unchanged order,
  state across swap/move/column changes, all-hidden/restored, malformed flags,
  and every one of the 64 visibility combinations.
- Editor behavioral instrumentation: tap/tap-back, gray tint and accessibility,
  unchanged measured positions/view identity, consumed failed long press,
  all-hidden editor reachability, reset and drag target/move state. These use
  an in-memory store seam rather than the installed user's real preferences.

## Manual checks after external compilation

1. Enable the new player; open Media → Video player → Button layout. Check the
   instruction, then hide and restore each of the six actions with short taps.
2. Open a video: hidden actions must be absent; visible actions must fill cells
   in the saved order. Hide all left actions: no left capsule or empty hit area.
3. Hide both right actions: playback, time and seek must still work.
4. Drag a gray action to another position/panel; it must remain hidden in the
   player and return to its new position when re-enabled. A drag must not toggle.
5. Change columns, rotate, leave/re-enter settings and cold-restart the app:
   placement, visibility and the measured panel backgrounds must remain correct.
6. Restore defaults: Speed/PiP/TikTok/Save left, Mute/Fullscreen right, 4 columns,
   every action enabled. Check light/dark/custom themes and accessibility.
7. Switch back to the old player and verify it retains its previous behavior.

The same snapshot also includes the preceding downloaded-update handoff fix;
its independent checklist is in DOWNLOADED_UPDATE_TEST_PLAN.md.
