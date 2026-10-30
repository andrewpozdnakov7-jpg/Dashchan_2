package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public class BackPreviewBudgetTest {
	@Test public void predictiveUsesHalfTheMemoryClassWith256MiBCeilingAndDisabledPolicyIsUnchanged() {
		long mib = 1024L * 1024;
		assertEquals(64 * mib, BackPreviewBudget.limitForMemoryClass(128, false, true));
		assertEquals(128 * mib, BackPreviewBudget.limitForMemoryClass(256, false, true));
		assertEquals(256 * mib, BackPreviewBudget.limitForMemoryClass(512, false, true));
		assertEquals(256 * mib, BackPreviewBudget.limitForMemoryClass(1024, false, true));
		assertEquals(64 * mib, BackPreviewBudget.limitForMemoryClass(128, true, true));
		assertEquals(48 * mib, BackPreviewBudget.limitForMemoryClass(512, false, false));
		assertEquals(12 * mib, BackPreviewBudget.limitForMemoryClass(512, true, false));
	}
	@Test public void reclaimingOneImagesWorthDoesNotDiscardTheWholeCacheOrRecycleALeasedImage() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(InstrumentationRegistry.getInstrumentation().getTargetContext()); view.layout(0, 0, 20, 40);
			long before = BackPreviewBudget.usedBytes();
			java.util.ArrayDeque<Bitmap> images = new java.util.ArrayDeque<>();
			BackPreviewBudget.Cache cache = () -> {
				Bitmap oldest = images.pollFirst();
				if (oldest == null) return false;
				BackPreviewBudget.release(oldest); return true;
			};
			Bitmap first = BackPreviewBudget.capture(view, 3200, android.graphics.Color.RED);
			Bitmap second = BackPreviewBudget.capture(view, 3200, android.graphics.Color.BLUE);
			assertNotNull(first); assertNotNull(second);
			images.add(first); images.add(second); BackPreviewBudget.registerCache(cache);
			boolean leased = false;
			try {
				long oneImage = first.getAllocationByteCount();
				long request = BackPreviewBudget.limit(view.getContext()) - before - oneImage;
				BackPreviewBudget.makeRoom(view.getContext(), request);
				assertTrue(first.isRecycled()); assertFalse(second.isRecycled()); assertEquals(1, images.size());
				leased = BackPreviewBudget.retain(second); assertTrue(leased);
				BackPreviewBudget.trimCaches(); assertFalse(second.isRecycled()); assertTrue(images.isEmpty());
				BackPreviewBudget.release(second); leased = false; assertTrue(second.isRecycled());
			} finally {
				while (cache.evictOldest()) {}
				if (leased) BackPreviewBudget.release(second);
				BackPreviewBudget.unregisterCache(cache);
			}
			assertEquals(before, BackPreviewBudget.usedBytes());
		});
	}
	@Test public void realAllocationsShareTheCapAndReleasingOneDoesNotReleaseAnActivePeer() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(InstrumentationRegistry.getInstrumentation().getTargetContext()); view.layout(0, 0, 1440, 3120);
			long before = BackPreviewBudget.usedBytes(), cap = BackPreviewBudget.limit(view.getContext());
			Bitmap destination = BackPreviewBudget.capture(view, cap / 2, android.graphics.Color.BLUE);
			Bitmap source = BackPreviewBudget.capture(view, cap / 2, android.graphics.Color.RED);
			try {
				assertNotNull(destination); assertNotNull(source);
				assertEquals(before + destination.getAllocationByteCount() + source.getAllocationByteCount(), BackPreviewBudget.usedBytes());
				assertTrue(BackPreviewBudget.usedBytes() <= cap);
				BackPreviewBudget.release(source); source = null;
				assertFalse(destination.isRecycled()); assertEquals(before + destination.getAllocationByteCount(), BackPreviewBudget.usedBytes());
			} finally { BackPreviewBudget.release(source); BackPreviewBudget.release(destination); }
			assertEquals(before, BackPreviewBudget.usedBytes());
		});
	}
	@Test public void fullHdQhdAndLowMemoryProfilesKeepAspectAndStayInsideTheirReservation() {
		for (int[] screen : new int[][] {{1080, 2400}, {1440, 3120}}) {
			int[] normal = BackPreviewBudget.dimensions(screen[0], screen[1], BackPreviewBudget.NORMAL_BYTES / 2);
			assertArrayEquals(screen, normal);
			int[] low = BackPreviewBudget.dimensions(screen[0], screen[1], BackPreviewBudget.LOW_MEMORY_BYTES / 2);
			assertNotNull(low); assertTrue(4L * low[0] * low[1] <= BackPreviewBudget.LOW_MEMORY_BYTES / 2);
			assertEquals(screen[0] / (float) screen[1], low[0] / (float) low[1], .002f);
		}
		assertNull(BackPreviewBudget.dimensions(0, 2400, 1024));
		assertNull(BackPreviewBudget.dimensions(1440, 3120, 0));
	}
}
