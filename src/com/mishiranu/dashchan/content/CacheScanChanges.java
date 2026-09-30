package com.mishiranu.dashchan.content;

import java.util.LinkedHashMap;
import java.util.Map;

/** Caller holds its index lock. Disk scanning itself must happen outside that lock. */
final class CacheScanChanges<T> {
	private long generation;
	private boolean building;
	private final LinkedHashMap<String, T> changes = new LinkedHashMap<>();

	long begin() {
		building = true;
		changes.clear();
		return ++generation;
	}

	boolean isBuilding() {
		return building;
	}

	void record(String key, T value) {
		if (building) {
			changes.remove(key);
			changes.put(key, value);
		}
	}

	boolean merge(long token, LinkedHashMap<String, T> snapshot) {
		if (token != generation || !building) return false;
		for (Map.Entry<String, T> entry : changes.entrySet()) {
			snapshot.remove(entry.getKey());
			if (entry.getValue() != null) snapshot.put(entry.getKey(), entry.getValue());
		}
		building = false;
		changes.clear();
		return true;
	}

	void fail(long token) {
		if (token == generation) {
			building = false;
			changes.clear();
		}
	}
}
