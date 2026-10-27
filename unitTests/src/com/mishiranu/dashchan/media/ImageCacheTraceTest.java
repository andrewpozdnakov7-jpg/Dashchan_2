package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import org.junit.Test;

/** Prepared, not executed. Synthetic resources only; no Android/device/network access. */
public class ImageCacheTraceTest {
	@Test public void aliasesCorrelateMediaWithThumbnailWithoutExportingUrlsPathsOrStableHashes() {
		ImageCacheTrace trace = new ImageCacheTrace();
		String media = "https://example.invalid/video.webm?token=synthetic-secret";
		String thumb = "file:///synthetic/private/thumbnail.png";
		String link = trace.record("pager", "select", media, thumb, "source=thumbnail", 0);
		String loaded = trace.record("loader", "disk_hit", thumb, null, "success=true", 0);
		assertTrue(link.contains("resource=1 related=2")); assertTrue(loaded.contains("resource=2 related=0"));
		for (String line : new String[]{link, loaded}) {
			assertFalse(line.contains("example.invalid")); assertFalse(line.contains("synthetic-secret"));
			assertFalse(line.contains("thumbnail.png")); assertFalse(line.contains("/private/"));
		}
	}
	@Test public void unchangedLookupsAreThrottledButChangedReasonIsImmediate() {
		ImageCacheTrace trace = new ImageCacheTrace();
		assertNotNull(trace.record("page", "miss", "a", null, "reason=not_stored", 100));
		assertNull(trace.record("page", "miss", "a", null, "reason=not_stored", 200));
		assertNotNull(trace.record("page", "miss", "a", null, "reason=position_changed", 201));
		assertNull(trace.record("page", "miss", "a", null, "reason=position_changed", 202));
		assertNotNull(trace.record("page", "miss", "a", null, "reason=position_changed", 1201));
	}
	@Test public void correlationAndEventTablesRemainBoundedAndResetBetweenCaptures() {
		ImageCacheTrace trace = new ImageCacheTrace();
		for (int i = 0; i < 2000; i++) trace.record("loader", "result", "synthetic-" + i, null, "success=true", i);
		assertEquals(512, trace.resourceCount()); assertEquals(256, trace.eventCount());
		trace.reset(); assertEquals(0, trace.resourceCount()); assertEquals(0, trace.eventCount());
		assertTrue(trace.record("page", "hit", "new-capture", null, "entries=1", 0).contains("resource=1 related=0"));
	}
	@Test public void oversizedInlineResourcesAreNotRetained() {
		ImageCacheTrace trace = new ImageCacheTrace();
		StringBuilder data = new StringBuilder("data:image/png;base64,"); while (data.length() < 4096) data.append('X');
		String line = trace.record("loader", "result", data.toString(), null, "source=inline_image", 0);
		assertEquals(0, trace.resourceCount()); assertFalse(line.contains("base64")); assertFalse(line.contains("XXXX"));
	}
}
