package com.mishiranu.dashchan.content;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

// Accessed on ImageLoader's main thread. Ordered by failure time, not lookup time:
// repeated requests must not prolong a failed URL's retry delay.
final class MissingImageCache {
	private final int capacity;
	private final long ttl;
	private final LongSupplier clock;
	private final LinkedHashMap<String, Long> entries = new LinkedHashMap<>();

	MissingImageCache(int capacity, long ttl, LongSupplier clock) {
		if (capacity <= 0 || ttl <= 0) throw new IllegalArgumentException();
		this.capacity = capacity;
		this.ttl = ttl;
		this.clock = clock;
	}

	private void prune(long now) {
		Iterator<Map.Entry<String, Long>> iterator = entries.entrySet().iterator();
		while (iterator.hasNext()) {
			long age = now - iterator.next().getValue();
			if (age >= 0 && age < ttl) break;
			iterator.remove();
		}
	}

	boolean contains(String key) {
		prune(clock.getAsLong());
		return entries.containsKey(key);
	}

	void put(String key) {
		long now = clock.getAsLong();
		prune(now);
		entries.remove(key);
		entries.put(key, now);
		if (entries.size() > capacity) {
			Iterator<String> iterator = entries.keySet().iterator();
			iterator.next();
			iterator.remove();
		}
	}

	void remove(String key) {
		entries.remove(key);
	}

	int size() {
		prune(clock.getAsLong());
		return entries.size();
	}
}
