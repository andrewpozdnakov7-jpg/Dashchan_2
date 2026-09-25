package com.mishiranu.dashchan.util;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/** Caller-confined cache. Eviction only drops references; values may still be displayed by a View. */
public final class WeightedLruCache<K, V> {
	private static final class Entry<V> {
		final V value;
		final long weight;
		Entry(V value, long weight) {
			this.value = value;
			this.weight = weight;
		}
	}

	private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
	private final long maxWeight;
	private final int maxEntries;
	private final ToLongFunction<V> weigh;
	private long weight;

	public WeightedLruCache(long maxWeight, int maxEntries, ToLongFunction<V> weigh) {
		if (maxWeight <= 0 || maxEntries <= 0) throw new IllegalArgumentException();
		this.maxWeight = maxWeight;
		this.maxEntries = maxEntries;
		this.weigh = weigh;
	}

	public V get(K key) {
		Entry<V> entry = entries.get(key);
		return entry != null ? entry.value : null;
	}

	public void put(K key, V value) {
		long cost = weigh.applyAsLong(value);
		if (cost <= 0) throw new IllegalArgumentException();
		Entry<V> previous = entries.remove(key);
		if (previous != null) weight -= previous.weight;
		if (cost > maxWeight) return;
		trimToWeight(maxWeight - cost);
		entries.put(key, new Entry<>(value, cost));
		weight += cost;
		trimToWeight(maxWeight);
	}

	public void trimToWeight(long limit) {
		limit = Math.max(0L, limit);
		Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
		while ((weight > limit || entries.size() > maxEntries) && iterator.hasNext()) {
			weight -= iterator.next().getValue().weight;
			iterator.remove();
		}
	}

	public long weight() {
		return weight;
	}

	public int size() {
		return entries.size();
	}
}
