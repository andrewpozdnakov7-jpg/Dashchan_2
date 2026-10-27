package com.mishiranu.dashchan.media;

import java.util.LinkedHashMap;

/** Capture-local correlation; output contains numeric aliases, never resource strings or stable hashes. */
final class ImageCacheTrace {
	private final LinkedHashMap<String, Integer> resources = new LinkedHashMap<>();
	private final LinkedHashMap<String, Event> events = new LinkedHashMap<>();
	private int nextResource;
	private static final class Event {
		final String state; final long at;
		Event(String state, long at) { this.state = state; this.at = at; }
	}
	void reset() { resources.clear(); events.clear(); nextResource = 0; }
	private int resourceId(String value) {
		if (value == null) return 0;
		// Do not retain large inline data URIs or unbounded resource strings for diagnostics.
		if (value.length() > 2048) return ++nextResource;
		Integer id = resources.get(value); if (id != null) return id;
		if (resources.size() >= 512) resources.remove(resources.keySet().iterator().next());
		id = ++nextResource; resources.put(value, id); return id;
	}
	/** Labels/state must be developer-owned tokens/numeric measurements, never resource-derived content. */
	String record(String scope, String event, String resource, String related, String state, long now) {
		int id = resourceId(resource), relatedId = resourceId(related);
		String key = scope + ":" + event + ":" + id + ":" + relatedId;
		Event previous = events.get(key);
		if (previous != null && previous.state.equals(state) && now - previous.at < 1000L) return null;
		if (events.size() >= 256 && !events.containsKey(key)) events.remove(events.keySet().iterator().next());
		events.put(key, new Event(state, now));
		return "image_cache scope=" + scope + " event=" + event + " resource=" + id + " related=" + relatedId + " " + state;
	}
	int resourceCount() { return resources.size(); }
	int eventCount() { return events.size(); }
}
