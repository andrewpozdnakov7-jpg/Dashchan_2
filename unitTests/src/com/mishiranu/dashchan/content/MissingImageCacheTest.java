package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class MissingImageCacheTest {
	@Test public void expiresAtTtlWithoutExtendingOnLookup() {
		AtomicLong clock = new AtomicLong();
		MissingImageCache cache = new MissingImageCache(2, 100, clock::get);
		cache.put("a");
		clock.set(99);
		assertTrue(cache.contains("a"));
		clock.set(100);
		assertFalse(cache.contains("a"));
		assertEquals(0, cache.size());
	}

	@Test public void uniqueFailuresStayBounded() {
		MissingImageCache cache = new MissingImageCache(3, 100, () -> 0);
		for (int i = 0; i < 10000; i++) cache.put(Integer.toString(i));
		assertEquals(3, cache.size());
		assertFalse(cache.contains("9996"));
		assertTrue(cache.contains("9997"));
		assertTrue(cache.contains("9999"));
	}

	@Test public void successCanRemoveNegativeResult() {
		MissingImageCache cache = new MissingImageCache(2, 100, () -> 0);
		cache.put("a");
		cache.remove("a");
		assertFalse(cache.contains("a"));
	}

	@Test public void expiresOldKeysEvenWhenLookingUpAnotherKey() {
		AtomicLong clock = new AtomicLong();
		MissingImageCache cache = new MissingImageCache(3, 100, clock::get);
		cache.put("old");
		clock.set(50);
		cache.put("recent");
		clock.set(100);
		assertFalse(cache.contains("absent"));
		assertEquals(1, cache.size());
		assertTrue(cache.contains("recent"));
	}

	@Test public void repeatedFailureMovesKeyToNewFailureTime() {
		AtomicLong clock = new AtomicLong();
		MissingImageCache cache = new MissingImageCache(3, 100, clock::get);
		cache.put("a");
		cache.put("b");
		clock.set(50);
		cache.put("a");
		clock.set(100);
		assertFalse(cache.contains("b"));
		assertTrue(cache.contains("a"));
		assertEquals(1, cache.size());
	}
}
