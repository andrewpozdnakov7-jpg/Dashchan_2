package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class WeightedLruCacheTest {
	@Test public void evictsLeastRecentlyUsedByBytes() {
		WeightedLruCache<String, Long> cache = new WeightedLruCache<>(10, 10, Long::longValue);
		cache.put("a", 4L);
		cache.put("b", 4L);
		assertNotNull(cache.get("a"));
		cache.put("c", 4L);
		assertNull(cache.get("b"));
		assertNotNull(cache.get("a"));
		assertEquals(8L, cache.weight());
	}

	@Test public void replacementCountsNewAllocationOnly() {
		WeightedLruCache<String, Long> cache = new WeightedLruCache<>(10, 10, Long::longValue);
		cache.put("a", 7L);
		cache.put("a", 3L);
		assertEquals(3L, cache.weight());
		assertEquals(1, cache.size());
		cache.put("a", 11L);
		assertNull(cache.get("a"));
		assertEquals(0L, cache.weight());
	}

	@Test public void oversizedEntryDoesNotEvictUnrelatedValues() {
		WeightedLruCache<String, Long> cache = new WeightedLruCache<>(10, 10, Long::longValue);
		cache.put("a", 5L);
		cache.put("large", 11L);
		assertNotNull(cache.get("a"));
		assertNull(cache.get("large"));
	}

	@Test public void countLimitAlsoBoundsTinyEntries() {
		WeightedLruCache<String, Long> cache = new WeightedLruCache<>(100, 2, Long::longValue);
		cache.put("a", 1L);
		cache.put("b", 1L);
		cache.put("c", 1L);
		assertEquals(2, cache.size());
		assertNull(cache.get("a"));
		cache.trimToWeight(0);
		assertEquals(0L, cache.weight());
		assertEquals(0, cache.size());
	}

	@Test public void trimmingDoesNotMutateBorrowedValue() {
		WeightedLruCache<String, byte[]> cache = new WeightedLruCache<>(10, 10, value -> value.length);
		byte[] displayed = {1, 2, 3};
		cache.put("a", displayed);
		assertSame(displayed, cache.get("a"));
		cache.trimToWeight(0);
		assertArrayEquals(new byte[] {1, 2, 3}, displayed);
	}
}
