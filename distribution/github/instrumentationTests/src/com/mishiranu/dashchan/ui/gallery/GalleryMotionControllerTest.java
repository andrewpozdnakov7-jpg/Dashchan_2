package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.widget.PhotoView;
import com.mishiranu.dashchan.widget.ThemeEngine;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Cancellation and rendering checks; does not change the system animator scale. */
@RunWith(AndroidJUnit4.class)
public class GalleryMotionControllerTest {
	private static void fixture(java.util.function.Consumer<Fixture> run) {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
			String key = Preferences.KEY_NEW_INTERFACE_MOTION;
			Object previous = Preferences.PREFERENCES.getAll().get(key);
			Fixture fixture = null;
			try {
				Preferences.PREFERENCES.edit().put(key, true).close();
				fixture = new Fixture(); run.accept(fixture);
			} finally {
				if (fixture != null) {
					fixture.motion.finish(); fixture.photo.finishGalleryPresentation();
					fixture.photo.recycle(); fixture.bitmap.recycle();
				}
				if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(key, (Boolean) previous).close();
				else Preferences.PREFERENCES.edit().remove(key).close();
			}
		});
	}
	private static final class Fixture {
		final android.content.Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
		final FrameLayout root = new FrameLayout(context);
		final PhotoView photo = new PhotoView(context, null);
		final View chrome = new View(context);
		final Bitmap bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888);
		final GalleryMotionController motion = new GalleryMotionController(root);
		Fixture() {
			bitmap.setDensity(Bitmap.DENSITY_NONE); bitmap.eraseColor(Color.BLUE);
			root.addView(photo); root.addView(chrome); root.layout(0, 0, 200, 200);
			photo.layout(0, 0, 200, 200); chrome.layout(0, 0, 200, 40);
			root.setBackgroundColor(Color.BLACK); root.getBackground().setAlpha(191);
			photo.setImage(new BitmapDrawable(context.getResources(), bitmap), false, false, false);
			photo.setAlpha(.7f); chrome.setAlpha(.8f);
			root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
			chrome.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
		}
		void assertReleased() {
			assertNull(root.getTag(R.id.gallery_motion_owner)); assertNull(photo.getTag(R.id.gallery_motion_owner));
			assertNull(chrome.getTag(R.id.gallery_motion_owner));
			assertEquals(.7f, photo.getAlpha(), 0f); assertEquals(.8f, chrome.getAlpha(), 0f);
			assertEquals(191, root.getBackground().getAlpha());
			assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, root.getImportantForAccessibility());
			assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, chrome.getImportantForAccessibility());
			root.getViewTreeObserver().dispatchOnPreDraw();
			assertNull(root.getTag(R.id.gallery_motion_owner));
		}
	}
	@Test public void interruptedEntranceRestoresChromeBackgroundAndRemovesLatePreDraw() {
		fixture(f -> {
			f.motion.enter(f.photo, new Object(), f.chrome, false);
			assertEquals(0f, f.photo.getAlpha(), 0f); assertTrue(GalleryMotionController.blocks(f.root));
			f.motion.finish(); f.motion.finish(); f.assertReleased();
		});
	}
	@Test public void resizedPreparingExitCompletesOnlyItsOwnDismissOnce() {
		fixture(f -> {
			int[] closes = {0};
			f.motion.install(f.motion.new Scene(f.photo, f.chrome, true, () -> closes[0]++, new Object()));
			f.root.layout(0, 0, 220, 200); f.motion.finish(); GalleryMotionController.finishHost(f.root);
			assertEquals(1, closes[0]); f.assertReleased();
		});
	}
	@Test public void restorationDoesNotReplayEntrance() {
		fixture(f -> { f.motion.enter(f.photo, new Object(), f.chrome, true); f.assertReleased(); });
	}
	@Test public void instantModeSettlesVisibilityAndReleasesAccessibility() {
		fixture(f -> {
			assertTrue(GalleryMotionController.mode(f.photo, false, 0, false));
			assertEquals(View.GONE, f.photo.getVisibility());
			assertNull(f.photo.getTag(R.id.gallery_motion_owner));
			assertEquals(1f, f.photo.getAlpha(), 0f);
			assertTrue(GalleryMotionController.mode(f.photo, true, 0, false));
			assertEquals(View.VISIBLE, f.photo.getVisibility());
			assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, f.photo.getImportantForAccessibility());
		});
	}
	@Test public void disabledPolicyLeavesLegacyModeRenderingUntouched() {
		fixture(f -> {
			Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, false).close();
			f.photo.setScaleX(.9f); f.photo.setScaleY(.85f);
			assertFalse(GalleryMotionController.mode(f.photo, false, 300, true));
			assertEquals(.7f, f.photo.getAlpha(), 0f);
			assertEquals(.9f, f.photo.getScaleX(), 0f);
			assertEquals(.85f, f.photo.getScaleY(), 0f);
			assertEquals(View.VISIBLE, f.photo.getVisibility());
			assertNull(f.photo.getTag(R.id.gallery_motion_owner));
		});
	}
	private static final class SwipeListener implements PhotoView.Listener {
		float value;
		int completedCloses;
		@Override public void onClick(PhotoView photo, boolean image, float x, float y) {}
		@Override public boolean onDoubleClick(PhotoView photo, float x, float y) { return false; }
		@Override public void onLongClick(PhotoView photo, float x, float y) {}
		@Override public void onTransformChanged(PhotoView photo, float l, float t, float r, float b) {}
		@Override public void onVerticalSwipe(PhotoView photo, boolean down, float value) { this.value = value; }
		@Override public boolean onClose(PhotoView photo, boolean down) { return false; }
		@Override public void onCloseAnimationFinished(PhotoView photo) { completedCloses++; }
		@Override public boolean isPhotoMotionEnabled(PhotoView photo) { return true; }
	}
	private static void dispatch(PhotoView photo, long down, long when, int action, float y) {
		MotionEvent event = MotionEvent.obtain(down, when, action, 100f, y, 0);
		try { photo.dispatchSpecialTouchEvent(event); } finally { event.recycle(); }
	}
	private static RectF startSwipeRecovery(Fixture f, SwipeListener listener) {
		f.photo.layout(0, 0, 600, 600); f.photo.setListener(listener);
		RectF before = f.photo.getGalleryImageBounds(); assertNotNull(before);
		long now = SystemClock.uptimeMillis(), down = now - 2000L;
		float distance = Math.max(40f, 2f * ViewConfiguration.get(f.context).getScaledTouchSlop());
		dispatch(f.photo, down, down, MotionEvent.ACTION_DOWN, 100f);
		dispatch(f.photo, down, now - 1000L, MotionEvent.ACTION_MOVE, 100f + distance);
		dispatch(f.photo, down, now, MotionEvent.ACTION_UP, 100f + distance);
		assertTrue(listener.value > 0f);
		return before;
	}
	private static void assertRecovered(Fixture f, SwipeListener listener, RectF before) {
		RectF after = f.photo.getGalleryImageBounds(); assertNotNull(after);
		assertEquals(before.left, after.left, .001f); assertEquals(before.top, after.top, .001f);
		assertEquals(before.right, after.right, .001f); assertEquals(before.bottom, after.bottom, .001f);
		assertEquals(0f, listener.value, 0f); assertEquals(0, listener.completedCloses);
		assertFalse(f.photo.isClosingTouchMode()); assertTrue(f.photo.isGalleryImageAtRest());
	}
	@Test public void nextTapDuringSwipeRecoveryRestoresImageAndScrimBeforeAcceptingInput() {
		fixture(f -> {
			SwipeListener listener = new SwipeListener(); RectF before = startSwipeRecovery(f, listener);
			long now = SystemClock.uptimeMillis();
			dispatch(f.photo, now, now, MotionEvent.ACTION_DOWN, 100f);
			try { assertRecovered(f, listener, before); }
			finally { dispatch(f.photo, now, now + 1L, MotionEvent.ACTION_CANCEL, 100f); }
		});
	}
	@Test public void presentationCleanupDuringSwipeRecoverySettlesBothGeometryAndScrim() {
		fixture(f -> {
			SwipeListener listener = new SwipeListener(); RectF before = startSwipeRecovery(f, listener);
			f.photo.finishGalleryPresentation(); f.photo.finishGalleryPresentation();
			assertRecovered(f, listener, before);
		});
	}
	@Test public void replacementImageReleasesOldSceneWithoutChangingItsNativeBounds() {
		fixture(f -> {
			RectF before = f.photo.getGalleryImageBounds();
			f.motion.enter(f.photo, new Object(), f.chrome, false);
			f.photo.setImage(new BitmapDrawable(f.context.getResources(), f.bitmap), false, false, true);
			f.assertReleased(); assertEquals(before, f.photo.getGalleryImageBounds());
		});
	}
	@Test public void renderFrameClipsUniformlyAndNeverWritesGestureGeometry() {
		fixture(f -> {
			RectF before = f.photo.getGalleryImageBounds();
			Object owner = new Object(); f.photo.setTag(R.id.gallery_motion_owner, owner);
			Bitmap canvasBitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
			try {
				f.photo.setGalleryFrame(owner, new RectF(20, 30, 80, 90), true);
				f.photo.draw(new Canvas(canvasBitmap));
				assertEquals(Color.TRANSPARENT, canvasBitmap.getPixel(10, 50));
				assertEquals(Color.BLUE, canvasBitmap.getPixel(50, 50));
				assertEquals(before, f.photo.getGalleryImageBounds());
				f.photo.clearGalleryFrame(owner); f.photo.setTag(R.id.gallery_motion_owner, null);
				assertEquals(before, f.photo.getGalleryImageBounds());
			} finally { f.photo.clearGalleryFrame(owner); f.photo.setTag(R.id.gallery_motion_owner, null); canvasBitmap.recycle(); }
		});
	}
	@Test public void oldPreviewIsDrawnAboveNewImageAndRecycledOnInterruption() {
		fixture(f -> {
			Bitmap old = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888); old.eraseColor(Color.RED);
			GalleryMotionController.Preview preview = new GalleryMotionController.Preview(old);
			GalleryMotionController.Reveal reveal = new GalleryMotionController.Reveal(f.photo, preview);
			Bitmap output = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
			try {
				f.photo.setAlpha(1f); reveal.start(); f.photo.draw(new Canvas(output));
				assertEquals(Color.RED, output.getPixel(100, 100));
				f.photo.finishGalleryPresentation(); reveal.run();
				assertTrue(old.isRecycled()); assertNull(f.photo.getTag(R.id.gallery_motion_owner));
				output.eraseColor(Color.TRANSPARENT); f.photo.draw(new Canvas(output));
				assertEquals(Color.BLUE, output.getPixel(100, 100)); assertEquals(1f, f.photo.getAlpha(), 0f);
			} finally { reveal.run(); output.recycle(); }
		});
	}

	@Test public void videoContainerFrameRestoresGeometryAndKeepsItsChildAttached() {
		fixture(f -> {
			FrameLayout surface = new FrameLayout(f.context);
			View output = new View(f.context); surface.addView(output);
			surface.layout(0, 0, 200, 200);
			surface.setAlpha(.6f); surface.setTranslationX(3f); surface.setTranslationY(4f);
			surface.setScaleX(.9f); surface.setScaleY(.8f); surface.setPivotX(30f); surface.setPivotY(40f);
			Rect originalClip = new Rect(0, 0, 190, 195); surface.setClipBounds(originalClip);
			GalleryMotionController.PresentationFrame frame = new GalleryMotionController.PresentationFrame(surface);
			Object owner = new Object(); frame.own(owner);
			frame.frame(owner, new RectF(0, 50, 200, 150), new RectF(20, 30, 80, 90), true);
			assertSame(surface, output.getParent()); assertEquals(surface.getScaleX(), surface.getScaleY(), 0f);
			assertNotNull(surface.getClipBounds()); frame.fade(owner, .2f); frame.release(owner); frame.release(owner);
			assertEquals(.6f, surface.getAlpha(), 0f);
			assertEquals(3f, surface.getTranslationX(), 0f); assertEquals(4f, surface.getTranslationY(), 0f);
			assertEquals(.9f, surface.getScaleX(), 0f); assertEquals(.8f, surface.getScaleY(), 0f);
			assertEquals(30f, surface.getPivotX(), 0f); assertEquals(40f, surface.getPivotY(), 0f);
			assertEquals(originalClip, surface.getClipBounds()); assertNull(surface.getTag(R.id.gallery_motion_owner));
			assertSame(surface, output.getParent());
		});
	}

	@Test public void interruptionReleasesVideoContainerAndItsControlsWithoutDetach() {
		fixture(f -> {
			FrameLayout surface = new FrameLayout(f.context); View output = new View(f.context);
			surface.addView(output); f.root.addView(surface); surface.setAlpha(.6f);
			View controls = new View(f.context); f.root.addView(controls); controls.setAlpha(.5f);
			f.motion.setMediaChrome(new View[] {controls, null, controls});
			f.motion.enter(f.photo, new Object(), f.chrome, false, surface);
			assertEquals(0f, surface.getAlpha(), 0f); assertEquals(0f, controls.getAlpha(), 0f);
			f.motion.finish(); f.assertReleased();
			assertEquals(.6f, surface.getAlpha(), 0f); assertEquals(.5f, controls.getAlpha(), 0f);
			assertNull(surface.getTag(R.id.gallery_motion_owner)); assertNull(controls.getTag(R.id.gallery_motion_owner));
			assertSame(surface, output.getParent());
		});
	}
}
