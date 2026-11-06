package com.mishiranu.dashchan.media;

import java.util.LinkedHashMap;

/** Bounded metadata only. Caller serializes access; no pixels, Views, page data or exported keys. */
public final class BackPreviewTrace {
	private final String processMarker;
	private final int limit;
	private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
	private long evictions;
	private static final class Entry {
		final String origin;
		String event, state, removal = "none", capture = "none";
		long eventAt, storedAt = -1, removedAt = -1;
		Entry(String origin, String event, String state, long now) {
			this.origin = origin; this.event = event; this.state = state; eventAt = now;
		}
	}
	public BackPreviewTrace(String processMarker, int limit) {
		if (processMarker == null || limit <= 0) throw new IllegalArgumentException();
		this.processMarker = processMarker; this.limit = limit;
	}
	public String processMarker() { return processMarker; }
	public int size() { return entries.size(); }
	public long evictions() { return evictions; }
	private Entry put(String key, Entry entry) {
		if (entries.size() >= limit) {
			entries.remove(entries.keySet().iterator().next()); evictions++;
		}
		entries.put(key, entry); return entry;
	}
	public void register(String key, String savedProcessMarker, boolean restored, long now) {
		if (key == null || entries.containsKey(key)) return;
		String origin, event, state;
		if (!restored) {
			origin = "created_in_process"; event = "never_captured"; state = "not_captured";
		} else if (savedProcessMarker == null) {
			origin = "legacy_saved_route"; event = "legacy_state_without_snapshot_history"; state = "unobserved";
		} else if (!processMarker.equals(savedProcessMarker)) {
			origin = "restored_new_process"; event = "process_restart_snapshot_not_retained"; state = "not_retained";
		} else {
			origin = "restored_same_process"; event = "same_process_history_not_retained"; state = "unobserved";
		}
		put(key, new Entry(origin, event, state, now));
	}
	private Entry entry(String key, long now) {
		Entry entry = entries.get(key);
		return entry != null ? entry : put(key, new Entry("untracked_route",
				"history_not_retained", "unobserved", now));
	}
	public void record(String key, String event, boolean removed, long now) {
		if (key == null) return;
		Entry entry = entry(key, now);
		entry.event = event; entry.eventAt = now;
		if (!removed && (event.startsWith("capture_") || "stored".equals(event))) entry.capture = event;
		if (removed) {
			entry.removal = event; entry.removedAt = now; entry.state = "removed";
		} else if ("stored".equals(event)) {
			entry.storedAt = now; entry.state = "available";
		} else if ("capture_failed".equals(event) || "capture_discarded".equals(event)) {
			entry.state = event;
		}
	}
	public String describe(String key, long captureStarted, long now) {
		if (key == null) return "snapshot_history=no_destination";
		Entry entry = entry(key, now);
		return "snapshot_origin=" + entry.origin + " snapshot_state=" + entry.state
				+ " snapshot_last_event=" + entry.event
				+ " snapshot_event_ms=" + (entry.eventAt - captureStarted)
				+ " snapshot_event_before_capture=" + (entry.eventAt < captureStarted)
				+ " snapshot_event_age_ms=" + Math.max(0L, now - entry.eventAt)
				+ " snapshot_stored_ms=" + (entry.storedAt >= 0 ? entry.storedAt - captureStarted : -1)
				+ " snapshot_ever_stored=" + (entry.storedAt >= 0)
				+ " snapshot_last_capture=" + entry.capture
				+ " snapshot_stored_before_capture=" + (entry.storedAt >= 0 && entry.storedAt < captureStarted)
				+ " snapshot_last_removal=" + entry.removal
				+ " snapshot_removal_age_ms=" + (entry.removedAt >= 0 ? Math.max(0L, now - entry.removedAt) : -1);
	}
}
