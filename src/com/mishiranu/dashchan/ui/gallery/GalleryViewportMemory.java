package com.mishiranu.dashchan.ui.gallery;

import java.util.LinkedHashMap;

/** Bounded, session-only positions. No views, media objects, disk persistence or diagnostics. */
final class GalleryViewportMemory {
	static final int LIMIT = 16;
	static final class Snapshot {
		final String itemKey;
		final int offset;
		final int width;
		final int columns;

		Snapshot(String itemKey, int offset, int width, int columns) {
			this.itemKey = itemKey;
			this.offset = offset;
			this.width = width;
			this.columns = columns;
		}

		boolean isValid() {
			return itemKey != null && !itemKey.isEmpty() && itemKey.length() <= 8192
					&& width > 0 && columns > 0 && Math.abs((long) offset) <= 100000;
		}

		int offsetFor(int currentWidth, int currentColumns) {
			// A different grid geometry cannot reuse a pixel offset from a taller row.
			return width == currentWidth && columns == currentColumns ? offset : 0;
		}
	}

	private final LinkedHashMap<String, Snapshot> positions = new LinkedHashMap<>(LIMIT, .75f, true);

	Snapshot get(String scope) { return scope != null ? positions.get(scope) : null; }

	void put(String scope, Snapshot snapshot) {
		if (scope == null || snapshot == null || !snapshot.isValid()) return;
		positions.put(scope, snapshot);
		while (positions.size() > LIMIT) positions.remove(positions.keySet().iterator().next());
	}

	void clear() { positions.clear(); }
}
