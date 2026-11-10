package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Narrow lifecycle regression: the production focus/reset and invocation paths,
 * without constructing a player, loading a forum or requiring an OEM gesture. */
public class GalleryBackResetTest {
	private static void objectField(Object owner, Class<?> type, String name, Object value) {
		try {
			java.lang.reflect.Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
		} catch (ReflectiveOperationException error) { throw new AssertionError(error); }
	}
	private static ListUnit cacheOwner(GalleryOverlay overlay, android.graphics.Bitmap frame) {
		GalleryInstance.Callback callback = (GalleryInstance.Callback) java.lang.reflect.Proxy.newProxyInstance(
				GalleryInstance.Callback.class.getClassLoader(), new Class<?>[] {GalleryInstance.Callback.class},
				(proxy, method, arguments) -> {
					if (method.getReturnType() == boolean.class) return false;
					if (method.getReturnType() == void.class) return null;
					throw new AssertionError("Unexpected gallery callback: " + method.getName());
				});
		android.content.Context context = com.mishiranu.dashchan.widget.ThemeEngine.attach(
				InstrumentationRegistry.getInstrumentation().getTargetContext());
		ListUnit list = new ListUnit(new GalleryInstance(context, callback, android.graphics.Color.BLACK,
				null, new java.util.ArrayList<>(), null));
		objectField(list, ListUnit.class, "predictiveGrid", frame);
		objectField(overlay, GalleryOverlay.class, "listUnit", list);
		return list;
	}
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
	@Test public void pauseAndStopFinishHooksKeepTheSameGridAllocationForResume() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			GalleryOverlay overlay = new GalleryOverlay();
			android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888);
			ListUnit list = cacheOwner(overlay, frame);
			try {
				// Exercise the actual pause and dialog-stop finish hooks without a player/window.
				overlay.onPause(); assertTrue(list.hasPredictiveGrid()); assertFalse(frame.isRecycled());
				overlay.finishPredictiveBack(); assertTrue(list.hasPredictiveGrid()); assertFalse(frame.isRecycled());
				overlay.onResume(); assertTrue(list.hasPredictiveGrid()); assertFalse(frame.isRecycled());
			} finally { list.clearPredictiveGrid(); }
		});
	}
	@Test public void contentChangesAndConfirmedPressureStillReleaseTheRetainedGrid() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			GalleryOverlay overlay = new GalleryOverlay();
			android.graphics.Bitmap first = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888);
			ListUnit list = cacheOwner(overlay, first);
			try {
				list.onGalleryItemsChanged(); assertFalse(list.hasPredictiveGrid()); assertTrue(first.isRecycled());
				android.graphics.Bitmap second = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888);
				objectField(list, ListUnit.class, "predictiveGrid", second);
				try {
					java.lang.reflect.Method trim = GalleryOverlay.class.getDeclaredMethod("trimPredictivePreviews", String.class);
					trim.setAccessible(true); trim.invoke(overlay, "fixture_confirmed_pressure");
				} catch (ReflectiveOperationException error) { throw new AssertionError(error); }
				assertFalse(list.hasPredictiveGrid()); assertTrue(second.isRecycled());
			} finally { list.clearPredictiveGrid(); }
		});
	}
}
