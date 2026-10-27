package com.mishiranu.dashchan.ui.gallery;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Objects;

/** UI-thread, bounded frame references. Eviction never destroys a frame borrowed by a page. */
final class VideoPageFrames<T> {
	enum Transition {GRID, PAUSE, CONFIGURATION, ITEMS_CHANGED, CLOSE}
	interface Observer<T> {
		void onEvent(String event, String reason, VideoPreparationIdentity requested,
				VideoPreparationIdentity stored, T frame, int entries);
	}
	private final Observer<T> observer;
	VideoPageFrames() { this(null); }
	VideoPageFrames(Observer<T> observer) { this.observer = observer; }
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
		java.util.Iterator<Entry<T>> iterator = entries.values().iterator();
		while (iterator.hasNext()) {
			Entry<T> entry = iterator.next();
			if (!retains(entry.identity.resource)) {
				iterator.remove(); event("evict", "outside_window", entry.identity, entry, null);
			}
		}
	}

	boolean retains(String resource) {
		return resource != null && (Objects.equals(resource, current) || upcoming.contains(resource) || history.contains(resource));
	}

	T get(VideoPreparationIdentity identity) {
		if (identity == null) { event("miss", "no_identity", null, null, null); return null; }
		Entry<T> entry = entries.get(identity.resource);
		String mismatch = mismatch(identity, entry != null ? entry.identity : null);
		event(mismatch == null ? "hit" : "miss", mismatch != null ? mismatch : "exact",
				identity, entry, mismatch == null ? entry.frame : null);
		return mismatch == null ? entry.frame : null;
	}
	private static String mismatch(VideoPreparationIdentity requested, VideoPreparationIdentity stored) {
		if (stored == null) return "not_stored";
		if (!Objects.equals(requested.path, stored.path)) return "file_path_changed";
		if (requested.length != stored.length) return "file_size_changed";
		if (requested.modified != stored.modified) return "file_modified";
		if (requested.requestedPosition != stored.requestedPosition) return "position_changed";
		if (requested.seekAnyFrame != stored.seekAnyFrame) return "seek_policy_changed";
		if (requested.hardware != stored.hardware) return "decoder_policy_changed";
		return null;
	}

	void put(VideoPreparationIdentity identity, T frame) {
		if (identity == null || frame == null || !retains(identity.resource)) {
			event("put_rejected", identity == null ? "no_identity" : frame == null ? "no_frame" : "outside_window", identity, null, null);
			return;
		}
		Entry<T> previous = entries.put(identity.resource, new Entry<>(identity, frame));
		event(previous != null ? "replace" : "put", "published", identity, previous, frame);
	}

	void remove(String resource) {
		Entry<T> previous = entries.remove(resource); history.remove(resource);
		event("invalidate", "reload", previous != null ? previous.identity : null, previous, null);
	}
	/** Stopping a decoder is not a cache invalidation. Retain detached page images within this gallery owner. */
	void onTransition(Transition transition) {
		if (transition == Transition.CLOSE) clear("close");
		else event("retain", transition.name(), null, null, null);
	}
	void clear() { clear("explicit"); }
	private void clear(String reason) {
		int previousSize = entries.size();
		entries.clear(); history.clear(); upcoming.clear(); current = null;
		if (observer != null) observer.onEvent("clear", reason + "_previous_" + previousSize, null, null, null, 0);
	}
	private void event(String event, String reason, VideoPreparationIdentity requested, Entry<T> stored, T frame) {
		if (observer != null) observer.onEvent(event, reason, requested, stored != null ? stored.identity : null, frame, entries.size());
	}
	int size() { return entries.size(); }
}
