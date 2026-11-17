package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class LocalArchiveMediaPlanTest {
	private static class Storage implements LocalArchiveMediaPlan.Storage {
		final Map<String, Long> files = new HashMap<>();
		final Map<String, String> zip = new HashMap<>();
		@Override public boolean exists(String path) { return files.containsKey(path); }
		@Override public boolean complete(String path, long size) {
			Long actual = files.get(path);
			return actual != null && actual > 0 && (size <= 0 || actual == size);
		}
		@Override public String previousSource(String path, long expectedSize) { return zip.get(path); }
	}

	@Test public void updateReusesTwentyVideosAndQueuesOnlyFiveNewOnes() {
		Storage storage = new Storage();
		Map<String, String> sources = new HashMap<>();
		for (int i = 0; i < 20; i++) {
			storage.files.put("thread/src/" + i + ".webm", 100L);
			sources.put("thread/src/" + i + ".webm", "https://board/" + i + ".webm");
		}
		LocalArchiveMediaPlan plan = new LocalArchiveMediaPlan("thread", storage, sources, new HashMap<>(storage.files), storage.files.keySet());
		int pending = 0;
		for (int i = 0; i < 25; i++) {
			String url = "https://board/" + i + ".webm";
			LocalArchiveMediaPlan.Asset asset = plan.register("src", i + ".webm", url, url, null, 100);
			assertFalse(asset.missing);
			if (i < 20) { assertTrue(asset.reused); assertNull(asset.source); }
			else { assertEquals(url, asset.source); pending++; }
		}
		assertEquals(5, pending);
		assertEquals(25, plan.assets().size());
		assertEquals(20, storage.files.size());
	}

	@Test public void namesCollideCaseInsensitivelyButSourceIdentityDeduplicates() {
		Storage storage = new Storage();
		storage.files.put("thread/src/Photo.JPG", 10L);
		LocalArchiveMediaPlan plan = new LocalArchiveMediaPlan("thread", storage,
				Collections.singletonMap("thread/src/Photo.JPG", "old"), new HashMap<>(storage.files), storage.files.keySet());
		LocalArchiveMediaPlan.Asset other = plan.register("src", "photo.jpg", "new", "new", null, 10);
		assertEquals("thread/src/photo_1.jpg", other.path);
		assertSame(other, plan.register("src", "renamed.jpg", "new", "new", null, 10));
		assertTrue(plan.register("src", "photo.jpg", "old", "old", null, 10).reused);
	}

	@Test public void partialFileSurvivesRetryAndCompletedReplacementIsThenReused() {
		Storage storage = new Storage();
		storage.files.put("thread/src/video.mp4", 4L);
		Map<String, String> previous = Collections.singletonMap("thread/src/video.mp4", "video");
		LocalArchiveMediaPlan first = new LocalArchiveMediaPlan("thread", storage, previous,
				Collections.singletonMap("thread/src/video.mp4", 100L), storage.files.keySet());
		LocalArchiveMediaPlan.Asset retry = first.register("src", "video.mp4", "video", "video", null, 100);
		assertEquals("thread/src/video_1.mp4", retry.path);
		assertEquals(Long.valueOf(4), storage.files.get("thread/src/video.mp4"));
		storage.files.put(retry.path, 100L);
		LocalArchiveMediaPlan next = new LocalArchiveMediaPlan("thread", storage, first.sources(),
				Collections.singletonMap(retry.path, 100L), storage.files.keySet());
		LocalArchiveMediaPlan.Asset reused = next.register("src", "video.mp4", "video", "video", null, 100);
		assertEquals(retry.path, reused.path);
		assertTrue(reused.reused);
		assertNull(reused.source);
	}

	@Test public void missingLooseFileCanBeRestoredFromPreviousZipWithoutNetwork() {
		Storage storage = new Storage();
		storage.zip.put("thread/src/video.mp4", "slooop-archive://previous/thread/src/video.mp4?zipOnly=true");
		LocalArchiveMediaPlan plan = new LocalArchiveMediaPlan("thread", storage,
				Collections.singletonMap("thread/src/video.mp4", "remote"),
				Collections.singletonMap("thread/src/video.mp4", 100L), storage.files.keySet());
		LocalArchiveMediaPlan.Asset asset = plan.register("src", "video.mp4", "remote", "remote", null, 100);
		assertEquals("thread/src/video.mp4", asset.path);
		assertEquals(storage.zip.get(asset.path), asset.source);
		assertFalse(asset.missing);
	}

	@Test public void legacyFileWithoutAnyRemainingSourceMakesUpdatePartial() {
		Storage storage = new Storage();
		LocalArchiveMediaPlan plan = new LocalArchiveMediaPlan("thread", storage,
				Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());
		LocalArchiveMediaPlan.Asset asset = plan.register("src", "gone.mp4", null, null, "thread/src/gone.mp4", 100);
		assertTrue(asset.missing);
		assertFalse(asset.reused);
		assertNull(asset.source);
	}

	@Test public void unsafeLegacyPathsCannotEscapeTheResourceRoot() {
		Storage storage = new Storage();
		LocalArchiveMediaPlan plan = new LocalArchiveMediaPlan("thread", storage,
				Collections.singletonMap("thread/src/../other", "remote"), Collections.emptyMap(), Collections.emptyList());
		LocalArchiveMediaPlan.Asset asset = plan.register("src", "../other", "remote", "remote", "other/src/file", 100);
		assertEquals("thread/src/file", asset.path);
		assertFalse(LocalArchiveMediaPlan.child("thread", "src", "thread/src/../other"));
		assertFalse(LocalArchiveMediaPlan.child("thread", "src", "thread/src/.."));
	}

	@Test public void roundedBoardSizeDoesNotRejectAVerifiedCompleteFile() {
		Storage storage = new Storage();
		storage.files.put("thread/src/video.mp4", 102123L);
		LocalArchiveMediaPlan plan = new LocalArchiveMediaPlan("thread", storage,
				Collections.singletonMap("thread/src/video.mp4", "remote"), new HashMap<>(storage.files), storage.files.keySet());
		LocalArchiveMediaPlan.Asset asset = plan.register("src", "video.mp4", "remote", "remote", null, 102400);
		assertTrue(asset.reused);
		assertEquals(102123, asset.expectedSize);
		assertEquals(102400, asset.reportedSize);
		assertNull(asset.source);
	}

	@Test public void aNonemptyUnverifiedFileIsNotMistakenForASuccessfulDownload() {
		Storage storage = new Storage();
		storage.files.put("thread/src/video.mp4", 102400L);
		LocalArchiveMediaPlan plan = new LocalArchiveMediaPlan("thread", storage,
				Collections.singletonMap("thread/src/video.mp4", "remote"), Collections.emptyMap(), storage.files.keySet());
		LocalArchiveMediaPlan.Asset asset = plan.register("src", "video.mp4", "remote", "remote", null, 102400);
		assertFalse(asset.reused);
		assertEquals("thread/src/video_1.mp4", asset.path);
		assertEquals(0, asset.expectedSize);
		assertEquals("remote", asset.source);
	}
}
