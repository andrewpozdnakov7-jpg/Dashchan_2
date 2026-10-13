# Continuous thread fast-scroller position

## Cause

The previous thumb calculation divided the first visible item's fractional
position by `itemCount - 1`, then separately forced the position to 100% when
the list could no longer scroll down. With several posts still visible, these
two scales disagree: reaching the last pixel abruptly moves the thumb down.
Reversing from the end produces the opposite jump.

## Source change

For vertical, non-reversed linear lists, use the continuous adapter coordinates
at both viewport edges: `a` at the top, `b` at the bottom, and item count `N`.
The position is `a / (a + N - b)`. At the beginning `a` tends to zero; at the
end `b` tends to `N`, so the position reaches 100% without switching formulas.
Both edge coordinates include partial item heights, decorations and margins.
Only already attached edge views are read, not the entire thread.

Thumb drawing, drag targets and post-mark projection use this common viewport
scale. Drawing follows the actual laid-out content during and after dragging,
not an optimistic finger position followed by a different release formula.
An unmeasured drag target is resolved after layout by measuring only that item.
The last item is explicitly bottom-aligned, including a post taller than the
viewport. Pending adjustment is discarded on cancellation, lost focus,
detachment, disabled fast scrolling or a changed item count.

Mark positions and count remain an immutable data snapshot; the projection
changes continuously with the fractional visible span, not an integer count of
visible children. Tail marks are bounded at the end of the navigation scale.
The fixed thumb dimensions, colors, appearance animation, native/active
scrollbar exclusivity and other layout fallbacks are retained.

## Prepared coverage, not executed

- `FastScrollGeometryTest`: beginning/end, reverse scrolling, unequal heights,
  item boundaries, a single oversized item, fractional last-item navigation,
  empty lists and matching mark/navigation coordinates.
- `PaddedRecyclerViewFastScrollPositionTest`: actual LinearLayoutManager child
  bounds immediately before/at/after the bottom, with uniform posts, a tall
  first/last post and a single tall post.
- Existing native/active scrollbar exclusivity tests remain in place.

No compilation, Gradle, test execution, APK or device validation was performed
for this source-only change.

## Device handoff

1. Slowly scroll a thread of short posts to its end and reverse direction by
   a small distance: there must be no abrupt jump to/from the bottom.
2. Repeat with long first/last posts and with the previously recorded thread.
3. Drag and release the thumb in the middle and at both ends. Check that the
   last long post can be scrolled to its bottom and release does not jump.
4. Check post/reply marks, filtered threads, appended posts, rotation and
   disabling/re-enabling the active scrollbar. Only one scrollbar should show.
