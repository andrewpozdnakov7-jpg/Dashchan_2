package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.widget.AttachmentView;
import com.mishiranu.dashchan.widget.PhotoView;
import com.mishiranu.dashchan.widget.PredictiveTransition;
import com.mishiranu.dashchan.widget.StandardContentTestActivity;
import org.junit.Test;

/** Actual PhotoView rendering and binding guards in an attached window; no media decoding or network. */
public class PredictivePhotoPresentationTest {
	private static final class Fixture {
		final FrameLayout root;
		final PhotoView photo;
		final AttachmentView thumbnail;
		final View chrome;
		final Bitmap image = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888);
		Fixture(StandardContentTestActivity activity) {
			root = new FrameLayout(activity); root.setBackgroundColor(Color.BLACK);
			thumbnail = new AttachmentView(activity, null); root.addView(thumbnail, new FrameLayout.LayoutParams(48, 48));
			photo = new PhotoView(activity, null); root.addView(photo, new FrameLayout.LayoutParams(240, 360));
			chrome = new View(activity); root.addView(chrome, new FrameLayout.LayoutParams(240, 32));
			activity.setContentView(root);
			image.setDensity(Bitmap.DENSITY_NONE); image.eraseColor(Color.BLUE);
			photo.setImage(new BitmapDrawable(activity.getResources(), image), false, false, false);
			photo.setAlpha(.7f); chrome.setAlpha(.8f); root.getBackground().setAlpha(191);
		}
		Rect blueBounds() {
			Bitmap pixels = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888);
			try {
				photo.draw(new Canvas(pixels)); Rect bounds = new Rect();
				for (int y = 0; y < 360; y++) for (int x = 0; x < 240; x++) {
					int c = pixels.getPixel(x, y); if (Color.blue(c) > 100 && Color.alpha(c) > 100) bounds.union(x, y, x + 1, y + 1);
				}
				return bounds;
			} finally { pixels.recycle(); }
		}
		void assertRestored() {
			assertNull(photo.getTag(R.id.gallery_motion_owner)); assertNull(root.getTag(R.id.gallery_motion_owner));
			assertEquals(.7f, photo.getAlpha(), 0f); assertEquals(.8f, chrome.getAlpha(), 0f);
			assertEquals(191, root.getBackground().getAlpha());
		}
	}
	@Test public void imageFollowsItsOwnThumbnailInBothDirectionsAndRebindingRejectsCommit() {
		Fixture[] f = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; Rect original = s.blueBounds(); int[] dismiss = {0};
				PredictivePhotoPresentation.Target target = new PredictivePhotoPresentation.Target(s.thumbnail, s.photo);
				assertTrue(target.valid(s.photo));
				PredictiveTransition session = new PredictiveTransition(s.root,
						new PredictivePhotoPresentation(s.root, s.photo, s.chrome, target, null, null, () -> true));
				session.progress(.8f); Rect near = s.blueBounds(); assertTrue(near.width() < original.width());
				session.progress(.2f); assertTrue(s.blueBounds().width() > near.width());
				s.thumbnail.resetImage("different attachment"); session.progress(.5f);
				assertFalse(session.commit(() -> dismiss[0]++)); assertEquals(0, dismiss[0]);
				s.assertRestored(); assertEquals(original, s.blueBounds());
			});
		} finally { if (f[0] != null) f[0].image.recycle(); }
	}
	@Test public void gridPreviewDoesNotSwitchModeBeforeAcceptanceAndCompletionRunsOnlyOnce() {
		Fixture[] f = {null}; Bitmap grid = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888); grid.eraseColor(Color.GREEN);
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; int[] modeChanges = {0};
				PredictiveTransition cancel = new PredictiveTransition(s.root,
						new PredictivePhotoPresentation(s.root, s.photo, s.chrome, null, grid, new Rect(0, 0, 240, 360), () -> true));
				cancel.progress(.8f); cancel.progress(.2f); assertEquals(0, modeChanges[0]); cancel.finish(); s.assertRestored();
				PredictiveTransition commit = new PredictiveTransition(s.root,
						new PredictivePhotoPresentation(s.root, s.photo, s.chrome, null, grid, new Rect(0, 0, 240, 360), () -> true));
				commit.progress(.4f); assertTrue(commit.commit(() -> modeChanges[0]++)); commit.finish(); commit.finish();
				assertEquals(1, modeChanges[0]); s.assertRestored();
			});
		} finally { grid.recycle(); if (f[0] != null) f[0].image.recycle(); }
	}
}
