package com.mishiranu.dashchan.ui.gallery;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Objects;

/** UI-thread, bounded frame references. Eviction never destroys a frame borrowed by a page. */
final class VideoPageFrames<T> {
	private static final int HISTORY_LIMIT = 20;
	private static final int AHEAD_LIMIT = 5;
	private static final class Entry<T> {
		final VideoPreparationIdentity identity;
		final T frame;
		Entry(VideoPreparationIdentity identity, T frame) { this.identity = identity; this.frame = frame; }
	}
	private final LinkedHashMap<String, Entry<T>> entries = new LinkedHashMap<>();
	private final LinkedHashSet<String> history = new LinkedHashSet<>();
	private final LinkedHashSet<String> upcoming = new LinkedHashSet<>();
	private String current;

	/** Keep viewed history independent of speculative neighbours. This never decodes/downloads. */
	void setWindow(String selected, Collection<String> following) {
		if (!Objects.equals(current, selected)) {
			if (current != null && entries.containsKey(current)) {
				history.remove(current); history.add(current);
			}
			current = selected;
			history.remove(current);
		}
		upcoming.clear();
		for (String resource : following) {
			if (resource != null && !Objects.equals(resource, current)) upcoming.add(resource);
			if (upcoming.size() == AHEAD_LIMIT) break;
		}
		while (history.size() > HISTORY_LIMIT) history.remove(history.iterator().next());
		entries.keySet().removeIf(resource -> !retains(resource));
	}

	boolean retains(String resource) {
		return resource != null && (Objects.equals(resource, current) || upcoming.contains(resource) || history.contains(resource));
	}

	T get(VideoPreparationIdentity identity) {
		if (identity == null) return null;
		Entry<T> entry = entries.get(identity.resource);
		return entry != null && entry.identity.matches(identity) ? entry.frame : null;
	}

	void put(VideoPreparationIdentity identity, T frame) {
		if (identity == null || frame == null || !retains(identity.resource)) return;
		entries.put(identity.resource, new Entry<>(identity, frame));
	}

	void remove(String resource) { entries.remove(resource); history.remove(resource); }
	void clear() { entries.clear(); history.clear(); upcoming.clear(); current = null; }
	int size() { return entries.size(); }
}
