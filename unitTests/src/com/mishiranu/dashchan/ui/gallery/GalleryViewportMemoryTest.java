package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import org.junit.Test;

public class GalleryViewportMemoryTest {
	private static GalleryViewportMemory.Snapshot snapshot(String file) {
		return new GalleryViewportMemory.Snapshot(file, -37, 720, 3);
	}

	@Test public void reopeningThreadRetainsFileIdentityAndExactOffset() {
		GalleryViewportMemory memory = new GalleryViewportMemory();
		memory.put("thread", snapshot("attachment-A"));
		assertEquals("attachment-A", memory.get("thread").itemKey);
		assertEquals(-37, memory.get("thread").offsetFor(720, 3));
	}

	@Test public void differentGeometryKeepsIdentityButDoesNotReuseAnObsoletePixelOffset() {
		GalleryViewportMemory.Snapshot snapshot = snapshot("attachment-A");
		assertEquals(0, snapshot.offsetFor(1200, 5));
		assertEquals(0, snapshot.offsetFor(720, 2));
		assertEquals(-37, snapshot.offsetFor(720, 3));
	}

	@Test public void unrelatedScopesDoNotLeakPositions() {
		GalleryViewportMemory memory = new GalleryViewportMemory();
		memory.put("forum-A/thread-A/filter-all", snapshot("one"));
		memory.put("forum-B/thread-A/filter-all", snapshot("two"));
		assertNull(memory.get("forum-A/thread-B/filter-all"));
		assertNull(memory.get("forum-A/thread-A/filter-video"));
		assertEquals("one", memory.get("forum-A/thread-A/filter-all").itemKey);
		assertEquals("two", memory.get("forum-B/thread-A/filter-all").itemKey);
	}

	@Test public void sessionMemoryIsBoundedAndUsesLeastRecentlyUsedEviction() {
		GalleryViewportMemory memory = new GalleryViewportMemory();
		for (int i = 0; i < GalleryViewportMemory.LIMIT; i++) memory.put("thread-" + i, snapshot("file-" + i));
		assertNotNull(memory.get("thread-0"));
		memory.put("extra", snapshot("extra"));
		assertNull(memory.get("thread-1"));
		assertNotNull(memory.get("thread-0"));
	}

	@Test public void invalidSavedStateCannotReplaceValidPosition() {
		GalleryViewportMemory memory = new GalleryViewportMemory();
		memory.put("thread", snapshot("valid"));
		memory.put("thread", new GalleryViewportMemory.Snapshot(null, 0, 720, 3));
		memory.put("thread", new GalleryViewportMemory.Snapshot("file", Integer.MIN_VALUE, 720, 3));
		memory.put("thread", new GalleryViewportMemory.Snapshot("file", 0, 0, 0));
		assertEquals("valid", memory.get("thread").itemKey);
		assertNull(memory.get(null));
	}

	@Test public void clearingSessionDropsAllPositions() {
		GalleryViewportMemory memory = new GalleryViewportMemory();
		memory.put("thread", snapshot("file"));
		memory.clear();
		assertNull(memory.get("thread"));
	}
}
