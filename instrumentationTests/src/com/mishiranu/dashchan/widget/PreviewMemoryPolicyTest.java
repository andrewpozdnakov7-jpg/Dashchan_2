package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@SuppressWarnings("deprecation")
@RunWith(AndroidJUnit4.class)
public class PreviewMemoryPolicyTest {
	@Test public void hidingAndBackgroundingKeepPreviewsButConfirmedPressureClearsEachOwnerOnce() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			boolean[] low = {false}; int[] clears = {0, 0};
			PreviewMemoryPolicy.Dispatcher events = new PreviewMemoryPolicy.Dispatcher(context, () -> low[0], false);
			try (PreviewMemoryPolicy first = new PreviewMemoryPolicy(events, reason -> clears[0]++, null);
					PreviewMemoryPolicy second = new PreviewMemoryPolicy(events, reason -> clears[1]++, null)) {
				events.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
				events.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
				events.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
				assertArrayEquals(new int[] {0, 0}, clears);
				low[0] = true;
				events.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
				assertArrayEquals(new int[] {1, 1}, clears);
				low[0] = false;
				events.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
				assertArrayEquals(new int[] {2, 2}, clears);
				first.close(); first.close();
				events.onLowMemory();
				assertArrayEquals(new int[] {2, 3}, clears);
			}
			assertTrue(events.owners.isEmpty());
		});
	}
	@Test public void compatibleConfigurationDoesNotClearButDensityAndFontScaleDo() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			int[] clears = {0};
			PreviewMemoryPolicy.Dispatcher events = new PreviewMemoryPolicy.Dispatcher(context, () -> false, false);
			try (PreviewMemoryPolicy policy = new PreviewMemoryPolicy(events, reason -> clears[0]++, null)) {
				Configuration config = new Configuration(context.getResources().getConfiguration());
				events.onConfigurationChanged(config);
				config.keyboardHidden = config.keyboardHidden == Configuration.KEYBOARDHIDDEN_YES
						? Configuration.KEYBOARDHIDDEN_NO : Configuration.KEYBOARDHIDDEN_YES;
				events.onConfigurationChanged(config); assertEquals(0, clears[0]);
				config.densityDpi++; events.onConfigurationChanged(config); assertEquals(1, clears[0]);
				events.onConfigurationChanged(config); assertEquals(1, clears[0]);
				config.fontScale += .1f; events.onConfigurationChanged(config); assertEquals(2, clears[0]);
			}
		});
	}
	@Test public void pressureReleasesCachedImageButKeepsTheRenderersLeaseUntilItsOwnerFinishes() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			View view = new View(context); view.layout(0, 0, 20, 40);
			BackPreviewBudget.trimCaches();
			long before = BackPreviewBudget.usedBytes();
			Bitmap image = BackPreviewBudget.capture(view, 3200, android.graphics.Color.RED);
			assertNotNull(image);
			boolean[] cached = {true};
			BackPreviewBudget.Cache cache = () -> {
				if (!cached[0]) return false;
				cached[0] = false; BackPreviewBudget.release(image); return true;
			};
			PreviewMemoryPolicy.Dispatcher events = new PreviewMemoryPolicy.Dispatcher(context, () -> false, false);
			boolean leased = BackPreviewBudget.retain(image); assertTrue(leased);
			try (PreviewMemoryPolicy policy = new PreviewMemoryPolicy(events, reason -> cache.evictOldest(), cache)) {
				events.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
				assertTrue(cached[0]); assertFalse(image.isRecycled());
				events.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
				assertFalse(cached[0]); assertFalse(image.isRecycled());
				assertEquals(before + image.getAllocationByteCount(), BackPreviewBudget.usedBytes());
				BackPreviewBudget.release(image); leased = false;
				assertTrue(image.isRecycled());
			} finally { cache.evictOldest(); if (leased) BackPreviewBudget.release(image); }
			assertEquals(before, BackPreviewBudget.usedBytes());
		});
	}
}
