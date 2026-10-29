package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Narrow lifecycle regression: the production focus/reset and invocation paths,
 * without constructing a player, loading a forum or requiring an OEM gesture. */
public class GalleryBackResetTest {
	private static void set(Object owner, String name, boolean value) {
		try {
			java.lang.reflect.Field field = GalleryOverlay.class.getDeclaredField(name);
			field.setAccessible(true); field.setBoolean(owner, value);
		} catch (ReflectiveOperationException error) { throw new AssertionError(error); }
	}
	private static boolean get(Object owner, String name) {
		try {
			java.lang.reflect.Field field = GalleryOverlay.class.getDeclaredField(name);
			field.setAccessible(true); return field.getBoolean(owner);
		} catch (ReflectiveOperationException error) { throw new AssertionError(error); }
	}
	private static GalleryOverlay interruptedImeRoute() {
		GalleryOverlay overlay = new GalleryOverlay();
		// Seed a gesture pinned to a local action that disappears with focus loss.
		set(overlay, "galleryBackGestureStarted", true);
		set(overlay, "backGestureLocalAction", true);
		overlay.onPredictiveBackWindowFocusChanged(false);
		return overlay;
	}
	@Test public void focusReturnWithoutCancelAllowsTheNextInvocationOnlyBack() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			GalleryOverlay overlay = interruptedImeRoute();
			assertFalse(get(overlay, "galleryBackGestureStarted"));
			assertTrue(get(overlay, "galleryBackGestureAborted"));
			// Deliberately no onPredictiveBackCancelled or new start between events.
			overlay.onPredictiveBackWindowFocusChanged(true);
			assertFalse("Independent Back must reach the normal dialog/local route",
					overlay.onPredictiveBackCommitted());
		});
	}
	@Test public void lateInvocationBeforeFocusReturnIsConsumedOnlyOnce() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			GalleryOverlay overlay = interruptedImeRoute();
			assertTrue(overlay.onPredictiveBackCommitted());
			assertFalse(overlay.onPredictiveBackCommitted());
		});
	}
}
