package com.mishiranu.dashchan.content.async;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Main-thread owned request generations. Snapshots copy the map, not the result objects. */
public final class ProgressiveLoadState<T> {
	private final Map<String, T> results = new LinkedHashMap<>();
	private final Set<String> pending = new LinkedHashSet<>();
	private int generation;
	private int total;

	public int begin(Collection<String> sources, Predicate<T> retain) {
		generation++;
		pending.clear();
		pending.addAll(sources);
		total = pending.size();
		results.entrySet().removeIf(entry -> !pending.contains(entry.getKey()) || !retain.test(entry.getValue()));
		pending.removeAll(results.keySet());
		return generation;
	}

	public boolean complete(int generation, String source, T result) {
		if (generation != this.generation || !pending.remove(source)) return false;
		results.put(source, result);
		return true;
	}

	public boolean isPending(String source) {
		return pending.contains(source);
	}

	public int pendingCount() {
		return pending.size();
	}

	public int totalCount() {
		return total;
	}

	public Map<String, T> snapshot() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(results));
	}

	public void cancel() {
		generation++;
		pending.clear();
		results.clear();
		total = 0;
	}
}
