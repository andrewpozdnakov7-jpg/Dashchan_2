# Native and active scrollbar exclusivity

## Cause and scope

The page list requests both the native vertical scrollbar and the optional
active fast scroller. Previously it suppressed native drawing through
`onDrawVerticalScrollBar`, a hidden Android callback not declared in the public
SDK. That callback is absent from the optimized stable 3.2.59 APK, leaving both
scrollbars visible with different position metrics.

`PaddedRecyclerView` now remembers the caller's native scrollbar request and
uses the public `View.setVerticalScrollBarEnabled` API to suppress it whenever
the active fast scroller is enabled and the list is long enough. It restores
only the requested native fallback when the active scroller becomes unavailable.
The overlay is invalidated when availability changes and is not drawn while
unavailable, including any remaining hide animation.

The existing threshold, fast-scroll gestures, post marks, position metrics,
colors and user preferences were unchanged by this exclusivity patch. A later
position fix is documented in `FAST_SCROLLER_VIEWPORT_GEOMETRY.md`.
This patch does not modernize the
separate legacy gallery scrollbar geometry hook or broaden R8 keep rules.

## Prepared instrumentation coverage

`PaddedRecyclerViewScrollBarTest` exercises the actual framework draw flag:

- native-only and active-only selection, including repeated toggles;
- long/short/empty transitions and the exact two-viewport threshold;
- explicit native enable/disable while the active scroller owns drawing;
- context-only and both attribute constructors preserving their defaults;
- left-to-right and right-to-left layouts.

These tests are prepared, not executed as part of this source-only patch.
Compilation and optimized-APK/device validation remain pending.

## Device handoff

1. In an optimized release build, open a long thread with the active scrollbar
   enabled. Scroll and drag it: only one thumb should appear, with the existing
   post/reply marks and track behavior.
2. Stop scrolling, then reduce the list with a filter or switch to a short
   list. The old overlay must not fade on top of the native scrollbar.
3. Disable the active scrollbar and return to the long thread. Only the native
   scrollbar should remain. Re-enable it and repeat.
4. Check a board, rotation, insets and the opposite layout direction. Native-only
   lists and gallery browsing should retain their existing behavior.
