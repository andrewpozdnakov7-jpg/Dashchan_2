package com.mishiranu.dashchan.content.security;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

/** Bounded process-local presentation snapshots. Never persists notification content. */
public final class NotificationContentCache<T> {
	private static final int LIMIT = 32;
	private static final class Entry<T> {
		final String channel;
		final long when;
		final T value;
		Entry(String channel, long when, T value) { this.channel = channel; this.when = when; this.value = value; }
	}
	private final LinkedHashMap<String, Entry<T>> entries = new LinkedHashMap<>();
	public static String key(String tag, int id) { return id + ":" + (tag != null ? "tag:" + tag : "untagged"); }
	public synchronized void put(String tag, int id, String channel, long when, T value) {
		String key = key(tag, id);
		entries.remove(key);
		entries.put(key, new Entry<>(channel, when, value));
		while (entries.size() > LIMIT) entries.remove(entries.keySet().iterator().next());
	}
	public synchronized T get(String tag, int id, String channel, long when) {
		String key = key(tag, id);
		Entry<T> entry = entries.get(key);
		if (entry == null) return null;
		// An older active-notification snapshot may race a freshly prepared progress update.
		if (!Objects.equals(channel, entry.channel) || when != entry.when) return null;
		return entry.value;
	}
	public synchronized void retain(Set<String> active) { entries.keySet().retainAll(active); }
	public synchronized void clear() { entries.clear(); }
}
