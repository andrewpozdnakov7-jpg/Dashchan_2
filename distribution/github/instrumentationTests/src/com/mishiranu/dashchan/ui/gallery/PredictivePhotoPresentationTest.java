package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.AnimationDrawable;
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
	@Test public void loadingPreviewCanBePromotedDuringBackAndReversedWithoutLosingTheSession() {
		Fixture[] f = {null}; Bitmap full = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
		full.setDensity(Bitmap.DENSITY_NONE); full.eraseColor(Color.BLUE);
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; Object item = new Object();
				PredictivePhotoPresentation p = new PredictivePhotoPresentation(s.root, s.photo, s.chrome,
						new PredictivePhotoPresentation.Target(s.thumbnail, s.photo), null, null, () -> true,
						null, new View[0], item);
				PredictiveTransition session = new PredictiveTransition(s.root, p); p.setFinishSession(session::finish);
				session.progress(.55f); Rect before = s.blueBounds();
				BitmapDrawable decoded = new BitmapDrawable(a.getResources(), full);
				assertFalse(s.photo.replaceGalleryOpeningImage(new Object(), decoded, false, false, false));
				assertTrue(s.photo.replaceGalleryOpeningImage(item, decoded, false, false, false));
				assertTrue(session.isTracking()); assertTrue(p.valid()); assertSame(p, s.photo.getTag(R.id.gallery_motion_owner));
				Rect after = s.blueBounds();
				assertTrue(Math.abs(before.left - after.left) <= 1); assertTrue(Math.abs(before.top - after.top) <= 1);
				assertTrue(Math.abs(before.right - after.right) <= 1); assertTrue(Math.abs(before.bottom - after.bottom) <= 1);
				session.progress(.2f); assertTrue(s.blueBounds().width() > after.width());
				session.cancel(); session.finish(); s.assertRestored();
				Rect restored = s.blueBounds(); assertEquals(240, restored.width()); assertEquals(240, restored.height());
			});
		} finally { full.recycle(); if (f[0] != null) f[0].image.recycle(); }
	}
	@Test public void fullImageArrivingAfterAcceptanceKeepsExactlyOneCompletion() {
		Fixture[] f = {null}; Bitmap full = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888);
		full.setDensity(Bitmap.DENSITY_NONE); full.eraseColor(Color.BLUE);
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; Object item = new Object(); int[] closes = {0};
				PredictivePhotoPresentation p = new PredictivePhotoPresentation(s.root, s.photo, s.chrome,
						new PredictivePhotoPresentation.Target(s.thumbnail, s.photo), null, null, () -> true,
						null, new View[0], item);
				PredictiveTransition session = new PredictiveTransition(s.root, p); p.setFinishSession(session::finish);
				session.progress(.45f); assertTrue(session.commit(() -> closes[0]++));
				BitmapDrawable decoded = new BitmapDrawable(a.getResources(), full);
				assertTrue(s.photo.replaceGalleryOpeningImage(item, decoded, false, false, false)); assertTrue(p.valid());
				session.finish(); session.finish(); assertEquals(1, closes[0]); s.assertRestored();
				assertFalse(s.photo.replaceGalleryOpeningImage(item, decoded, false, false, false));
			});
		} finally { full.recycle(); if (f[0] != null) f[0].image.recycle(); }
	}
	@Test public void animatedDrawablePromotionAndFrameChangesKeepTheGestureOwner() {
		Fixture[] f = {null}; AnimationDrawable animation = new AnimationDrawable();
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; Object item = new Object();
				animation.addFrame(new BitmapDrawable(a.getResources(), s.image), 100);
				animation.addFrame(new BitmapDrawable(a.getResources(), s.image), 100);
				PredictivePhotoPresentation p = new PredictivePhotoPresentation(s.root, s.photo, s.chrome,
						new PredictivePhotoPresentation.Target(s.thumbnail, s.photo), null, null, () -> true,
						null, new View[0], item);
				PredictiveTransition session = new PredictiveTransition(s.root, p); p.setFinishSession(session::finish);
				session.progress(.4f);
				assertTrue(s.photo.replaceGalleryOpeningImage(item, animation, false, false, false));
				long generation = s.photo.getGalleryImageGeneration(); animation.start(); animation.selectDrawable(1);
				session.progress(.7f); assertTrue(session.isTracking()); assertTrue(p.valid());
				assertEquals(generation, s.photo.getGalleryImageGeneration());
				session.cancel(); session.finish(); s.assertRestored(); animation.stop();
			});
		} finally { animation.stop(); if (f[0] != null) f[0].image.recycle(); }
	}
	@Test public void emptyLoadingPageAcceptsItsFirstPreviewWithoutEndingBack() {
		Fixture[] f = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; s.photo.recycle(); assertFalse(s.photo.hasImage());
				assertTrue(s.photo.isPredictiveMediaAtRest()); Object item = new Object();
				PredictivePhotoPresentation p = new PredictivePhotoPresentation(s.root, s.photo, s.chrome,
						new PredictivePhotoPresentation.Target(s.thumbnail, s.photo), null, null, () -> true,
						null, new View[0], item);
				PredictiveTransition session = new PredictiveTransition(s.root, p); p.setFinishSession(session::finish);
				session.progress(.4f); assertTrue(session.isTracking());
				assertTrue(s.photo.replaceGalleryOpeningImage(item,
						new BitmapDrawable(a.getResources(), s.image), false, false, false));
				session.progress(.6f); assertTrue(p.valid()); assertTrue(session.isTracking());
				session.cancel(); session.finish(); s.assertRestored(); assertTrue(s.photo.hasImage());
			});
		} finally { if (f[0] != null) f[0].image.recycle(); }
	}
	@Test public void openingTransfersItsVisibleFrameAndOpacityBeforeAnyFurtherDraw() {
		Fixture[] f = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; Object item = new Object(); GalleryMotionController motion = new GalleryMotionController(s.root);
				GalleryMotionController.Scene entrance = motion.new Scene(s.photo, s.chrome, false, null, item);
				motion.install(entrance); entrance.image = s.photo.getGalleryImageBounds();
				entrance.thumbnailTarget = new PhotoTransitionGeometry.Target(s.thumbnail, s.photo);
				entrance.thumbnail = new android.graphics.RectF(entrance.thumbnailTarget.frame);
				entrance.crop = entrance.thumbnailTarget.crop; entrance.apply(.35f);
				Rect before = s.blueBounds(); float chromeAlpha = s.chrome.getAlpha(); int backgroundAlpha = s.root.getBackground().getAlpha();
				assertFalse(motion.canHandoffOpening(s.photo, new Object())); assertTrue(motion.canHandoffOpening(s.photo, item));
				GalleryMotionController.OpeningFrame opening = motion.takeOpening(s.photo, item); assertNotNull(opening);
				assertEquals(.65f, opening.fraction, .001f); assertNull(motion.takeOpening(s.photo, item));
				PredictivePhotoPresentation p = new PredictivePhotoPresentation(s.root, s.photo, s.chrome,
						new PredictivePhotoPresentation.Target(s.thumbnail, s.photo), null, null, () -> true,
						null, new View[0], item);
				p.startFromOpening(opening); PredictiveTransition session = new PredictiveTransition(s.root, p, opening.fraction);
				p.setFinishSession(session::finish); s.root.getViewTreeObserver().dispatchOnPreDraw();
				Rect after = s.blueBounds(); assertTrue(Math.abs(before.left - after.left) <= 1);
				assertTrue(Math.abs(before.top - after.top) <= 1); assertTrue(Math.abs(before.right - after.right) <= 1);
				assertTrue(Math.abs(before.bottom - after.bottom) <= 1); assertEquals(chromeAlpha, s.chrome.getAlpha(), .001f);
				assertEquals(backgroundAlpha, s.root.getBackground().getAlpha());
				session.progress(.6f); assertTrue(s.blueBounds().width() < after.width());
				session.cancel(); session.finish(); s.assertRestored(); assertEquals(240, s.blueBounds().width());
			});
		} finally { if (f[0] != null) f[0].image.recycle(); }
	}
}
