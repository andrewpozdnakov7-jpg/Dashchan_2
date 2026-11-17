package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import java.util.Collections;
import org.junit.Test;

/** ADDED / NOT RUN: presentation restoration must not resurrect stale notification content. */
public class NotificationContentCacheTest {
	@Test public void nullTagAndTextTagAreDifferentAndUpdatesReplaceTheSnapshot() {
		NotificationContentCache<String> cache = new NotificationContentCache<>();
		cache.put(null, 1, "downloads", 10, "first");
		cache.put("null", 1, "downloads", 10, "tagged");
		cache.put(null, 1, "downloads", 10, "updated");
		assertEquals("updated", cache.get(null, 1, "downloads", 10));
		assertEquals("tagged", cache.get("null", 1, "downloads", 10));
	}
	@Test public void reusedIdWithAnotherChannelOrTimestampCannotRestoreOldContent() {
		NotificationContentCache<String> cache = new NotificationContentCache<>();
		cache.put(null, 1, "downloads", 10, "old");
		assertNull(cache.get(null, 1, "posting", 10));
		cache.put(null, 1, "downloads", 10, "old");
		assertNull(cache.get(null, 1, "downloads", 11));
	}
	@Test public void anOldActiveSnapshotCannotRemoveTheNewlyPreparedPresentation() {
		NotificationContentCache<String> cache = new NotificationContentCache<>();
		cache.put(null, 1, "downloads", 11, "new");
		assertNull(cache.get(null, 1, "downloads", 10));
		assertEquals("new", cache.get(null, 1, "downloads", 11));
	}
	@Test public void dismissedNotificationsAreForgottenAndResetClearsAllContent() {
		NotificationContentCache<String> cache = new NotificationContentCache<>();
		cache.put(null, 1, "downloads", 10, "active");
		cache.put("dismissed", 2, "posting", 10, "old");
		cache.retain(Collections.singleton(NotificationContentCache.key(null, 1)));
		assertNull(cache.get("dismissed", 2, "posting", 10));
		assertEquals("active", cache.get(null, 1, "downloads", 10));
		cache.clear();
		assertNull(cache.get(null, 1, "downloads", 10));
	}
	@Test public void snapshotsAreBoundedAndRecentProgressWins() {
		NotificationContentCache<String> cache = new NotificationContentCache<>();
		for (int i = 0; i < 33; i++) cache.put(null, i, "downloads", 10, "snapshot" + i);
		assertNull(cache.get(null, 0, "downloads", 10));
		assertEquals("snapshot32", cache.get(null, 32, "downloads", 10));
	}
}
