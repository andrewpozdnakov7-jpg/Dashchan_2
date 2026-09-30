package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import org.junit.Test;

public class CacheScanChangesTest {
	@Test public void downloadDuringScanReplacesOldSizeWithoutDoubleCounting() {
		CacheScanChanges<Long> changes = new CacheScanChanges<>();
		long token = changes.begin();
		LinkedHashMap<String, Long> snapshot = new LinkedHashMap<>();
		snapshot.put("old", 20L);
		snapshot.put("updated", 10L);
		changes.record("updated", 50L);
		changes.record("new", 30L);
		assertTrue(changes.merge(token, snapshot));
		assertEquals(3, snapshot.size());
		assertEquals(100L, snapshot.values().stream().mapToLong(Long::longValue).sum());
	}

	@Test public void deletionAndFailedDownloadCannotBeResurrectedByScan() {
		CacheScanChanges<Long> changes = new CacheScanChanges<>();
		long token = changes.begin();
		LinkedHashMap<String, Long> snapshot = new LinkedHashMap<>();
		snapshot.put("deleted", 20L);
		snapshot.put("failed", 30L);
		changes.record("deleted", null);
		changes.record("failed", null);
		assertTrue(changes.merge(token, snapshot));
		assertTrue(snapshot.isEmpty());
	}

	@Test public void lastChangeWinsAndTouchesMoveToEnd() {
		CacheScanChanges<Long> changes = new CacheScanChanges<>();
		long token = changes.begin();
		LinkedHashMap<String, Long> snapshot = new LinkedHashMap<>();
		snapshot.put("a", 1L);
		snapshot.put("b", 2L);
		snapshot.put("c", 3L);
		changes.record("a", null);
		changes.record("b", 2L);
		changes.record("a", 10L);
		assertTrue(changes.merge(token, snapshot));
		assertEquals(Arrays.asList("c", "b", "a"), new ArrayList<>(snapshot.keySet()));
		assertEquals(Long.valueOf(10L), snapshot.get("a"));
	}

	@Test public void olderScanCannotConsumeChangesOfNewScan() {
		CacheScanChanges<Long> changes = new CacheScanChanges<>();
		long oldToken = changes.begin();
		long newToken = changes.begin();
		changes.record("new", 50L);
		assertFalse(changes.merge(oldToken, new LinkedHashMap<>()));
		assertTrue(changes.isBuilding());
		LinkedHashMap<String, Long> snapshot = new LinkedHashMap<>();
		assertTrue(changes.merge(newToken, snapshot));
		assertEquals(Long.valueOf(50L), snapshot.get("new"));
		assertFalse(changes.isBuilding());
	}

	@Test public void olderFailureDoesNotCancelCurrentScan() {
		CacheScanChanges<Long> changes = new CacheScanChanges<>();
		long oldToken = changes.begin();
		long token = changes.begin();
		changes.record("new", 7L);
		changes.fail(oldToken);
		assertTrue(changes.isBuilding());
		LinkedHashMap<String, Long> snapshot = new LinkedHashMap<>();
		assertTrue(changes.merge(token, snapshot));
		assertEquals(Long.valueOf(7L), snapshot.get("new"));
	}

	@Test public void failedScanCannotPublishAndNextScanCanRecover() {
		CacheScanChanges<Long> changes = new CacheScanChanges<>();
		long token = changes.begin();
		changes.record("old", 1L);
		changes.fail(token);
		assertFalse(changes.isBuilding());
		assertFalse(changes.merge(token, new LinkedHashMap<>()));
		long next = changes.begin();
		LinkedHashMap<String, Long> snapshot = new LinkedHashMap<>();
		assertTrue(changes.merge(next, snapshot));
		assertTrue(snapshot.isEmpty());
	}

	@Test public void mutationsOutsideScanAreNotRetainedForNextSnapshot() {
		CacheScanChanges<Long> changes = new CacheScanChanges<>();
		changes.record("before", 1L);
		long token = changes.begin();
		LinkedHashMap<String, Long> snapshot = new LinkedHashMap<>();
		assertTrue(changes.merge(token, snapshot));
		changes.record("after", 2L);
		assertFalse(changes.merge(token, snapshot));
		assertTrue(changes.merge(changes.begin(), snapshot));
		assertTrue(snapshot.isEmpty());
	}
}
