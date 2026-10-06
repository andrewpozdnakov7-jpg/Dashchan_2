package com.mishiranu.dashchan.ui.gallery;

import java.util.IdentityHashMap;
import java.util.List;

/** Maps a viewport anchor between filtered lists without relying on obsolete adapter indices. */
final class GalleryFilterPosition {
	private GalleryFilterPosition() {}

	static <T> int resolve(List<T> originalOrder, List<T> visibleItems, T anchor,
			boolean atStart, boolean atEnd, boolean preserveEdges) {
		if (visibleItems.isEmpty()) return -1;
		// A short, non-scrollable list is both atStart and atEnd: prefer its start.
		if (preserveEdges && atStart) return 0;
		if (preserveEdges && atEnd) return visibleItems.size() - 1;
		if (anchor == null) return -1;
		IdentityHashMap<T, Integer> positions = new IdentityHashMap<>();
		for (int i = 0; i < visibleItems.size(); i++) {
			positions.putIfAbsent(visibleItems.get(i), i);
		}
		Integer exact = positions.get(anchor);
		if (exact != null) return exact;
		int originalPosition = -1;
		for (int i = 0; i < originalOrder.size(); i++) {
			if (originalOrder.get(i) == anchor) {
				originalPosition = i;
				break;
			}
		}
		if (originalPosition < 0) return -1;
		// Use attachment order, not post numbers (one post can have several files).
		// Prefer the following attachment when both neighbours are equally close.
		for (int distance = 1; distance < originalOrder.size(); distance++) {
			int after = originalPosition + distance;
			if (after < originalOrder.size()) {
				Integer position = positions.get(originalOrder.get(after));
				if (position != null) return position;
			}
			int before = originalPosition - distance;
			if (before >= 0) {
				Integer position = positions.get(originalOrder.get(before));
				if (position != null) return position;
			}
		}
		return -1;
	}
}
