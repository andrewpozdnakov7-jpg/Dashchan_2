package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.view.TextureView;
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

/** Attached container/TextureView ownership fixtures; no decoder, audio or network is exercised. */
public class PredictiveVideoPresentationTest {
	private static final class Fixture {
		final FrameLayout root, surface;
		final PhotoView photo;
		final AttachmentView thumbnail;
		final TextureView texture;
		final View chrome, controls;
		final Bitmap image = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888);
		final Rect clip = new Rect(0, 0, 240, 360);
		boolean owned = true;
		Fixture(StandardContentTestActivity activity) {
			root = new FrameLayout(activity); root.setBackgroundColor(Color.BLACK);
			thumbnail = new AttachmentView(activity, null);
			root.addView(thumbnail, new FrameLayout.LayoutParams(48, 48));
			photo = new PhotoView(activity, null);
			root.addView(photo, new FrameLayout.LayoutParams(240, 360));
			surface = new FrameLayout(activity);
			root.addView(surface, new FrameLayout.LayoutParams(240, 360));
			texture = new TextureView(activity);
			surface.addView(texture, new FrameLayout.LayoutParams(240, 360));
			// Simulate an existing user transform on the player child. Back must not change it.
			texture.setPivotX(23f); texture.setPivotY(41f); texture.setScaleX(1.7f);
			texture.setScaleY(1.7f); texture.setTranslationX(-12f); texture.setTranslationY(8f);
			controls = new View(activity); controls.setAlpha(.6f);
			root.addView(controls, new FrameLayout.LayoutParams(240, 32));
			chrome = new View(activity); chrome.setAlpha(.8f);
			root.addView(chrome, new FrameLayout.LayoutParams(240, 32));
			surface.setPivotX(12f); surface.setPivotY(16f); surface.setClipBounds(clip); surface.setAlpha(.9f);
			activity.setContentView(root);
			image.setDensity(Bitmap.DENSITY_NONE); image.eraseColor(Color.BLUE);
			photo.setImage(new BitmapDrawable(activity.getResources(), image), false, false, false);
		}
		PredictiveTransition start() {
			PredictivePhotoPresentation p = new PredictivePhotoPresentation(root, photo, chrome,
					new PredictivePhotoPresentation.Target(thumbnail, photo), null, null, () -> owned,
					surface, new View[] {controls, controls, chrome});
			PredictiveTransition session = new PredictiveTransition(root, p);
			p.setFinishSession(session::finish);
			return session;
		}
		void assertChildUnchanged() {
			assertSame(surface, texture.getParent()); assertSame(texture, surface.getChildAt(0));
			assertEquals(1, surface.getChildCount()); assertEquals(1.7f, texture.getScaleX(), 0f);
			assertEquals(1.7f, texture.getScaleY(), 0f); assertEquals(23f, texture.getPivotX(), 0f);
			assertEquals(41f, texture.getPivotY(), 0f); assertEquals(-12f, texture.getTranslationX(), 0f);
			assertEquals(8f, texture.getTranslationY(), 0f); assertNull(texture.getTag(R.id.gallery_motion_owner));
		}
		void assertRestored() {
			assertChildUnchanged(); assertNull(surface.getTag(R.id.gallery_motion_owner));
			assertNull(photo.getTag(R.id.gallery_motion_owner)); assertNull(root.getTag(R.id.gallery_motion_owner));
			assertNull(controls.getTag(R.id.gallery_motion_owner));
			assertEquals(1f, surface.getScaleX(), 0f); assertEquals(1f, surface.getScaleY(), 0f);
			assertEquals(0f, surface.getTranslationX(), 0f); assertEquals(0f, surface.getTranslationY(), 0f);
			assertEquals(12f, surface.getPivotX(), 0f); assertEquals(16f, surface.getPivotY(), 0f);
			assertEquals(clip, surface.getClipBounds()); assertEquals(.9f, surface.getAlpha(), 0f);
			assertEquals(.6f, controls.getAlpha(), 0f); assertEquals(.8f, chrome.getAlpha(), 0f);
			assertEquals(255, root.getBackground().getAlpha());
		}
	}
	@Test public void progressAndCancellationTransformOnlyTheContainerAndRestoreEveryProperty() {
		Fixture[] f = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; PredictiveTransition session = s.start();
				session.progress(.8f); float scale = s.surface.getScaleX(); assertTrue(scale < 1f);
				assertTrue(s.controls.getAlpha() < .6f); s.assertChildUnchanged();
				session.progress(.2f); assertTrue(s.surface.getScaleX() > scale);
				session.cancel(); session.finish(); session.finish(); s.assertRestored();
			});
		} finally { if (f[0] != null) f[0].image.recycle(); }
	}
	@Test public void ownershipLossBeforeAcceptanceRestoresWithoutClosing() {
		Fixture[] f = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; int[] closes = {0}; PredictiveTransition session = s.start();
				session.progress(.5f); s.owned = false; session.progress(.7f);
				assertFalse(session.commit(() -> closes[0]++)); assertEquals(0, closes[0]); s.assertRestored();
			});
		} finally { if (f[0] != null) f[0].image.recycle(); }
	}
	@Test public void acceptedBackClosesExactlyOnceAndRestoresBeforeTheCallback() {
		Fixture[] f = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; int[] closes = {0}; PredictiveTransition session = s.start();
				session.progress(.5f); assertTrue(session.commit(() -> { s.assertRestored(); closes[0]++; }));
				session.finish(); session.finish(); assertEquals(1, closes[0]); s.assertRestored();
			});
		} finally { if (f[0] != null) f[0].image.recycle(); }
	}
	@Test public void loadingVideoAdoptsItsSurfaceAtTheCurrentProgressAndCancelRestoresBoth() {
		Fixture[] f = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; s.surface.removeView(s.texture); Object item = new Object();
				PredictivePhotoPresentation p = new PredictivePhotoPresentation(s.root, s.photo, s.chrome,
						new PredictivePhotoPresentation.Target(s.thumbnail, s.photo), null, null, () -> s.owned,
						null, new View[] {s.controls}, item);
				PredictiveTransition session = new PredictiveTransition(s.root, p); p.setFinishSession(session::finish);
				session.progress(.5f);
				assertTrue(s.photo.replaceGalleryOpeningImage(item, new BitmapDrawable(a.getResources(), s.image),
						false, false, false, 10f));
				s.surface.addView(s.texture, new FrameLayout.LayoutParams(240, 360));
				p.attachVideoSurface(s.surface, new View[] {s.controls}, () -> s.texture.getParent() == s.surface);
				assertTrue(session.isTracking()); assertTrue(p.valid()); assertTrue(s.surface.getScaleX() < 1f);
				s.assertChildUnchanged(); session.progress(.2f); session.cancel(); session.finish(); s.assertRestored();
			});
		} finally { if (f[0] != null) f[0].image.recycle(); }
	}
	@Test public void ownershipLossAfterSurfaceAdoptionRestoresWithoutAcceptingBack() {
		Fixture[] f = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; Object item = new Object(); boolean[] local = {true}; int[] closes = {0};
				PredictivePhotoPresentation p = new PredictivePhotoPresentation(s.root, s.photo, s.chrome,
						new PredictivePhotoPresentation.Target(s.thumbnail, s.photo), null, null, () -> s.owned,
						null, new View[] {s.controls}, item);
				PredictiveTransition session = new PredictiveTransition(s.root, p); p.setFinishSession(session::finish);
				session.progress(.4f); p.attachVideoSurface(s.surface, new View[] {s.controls}, () -> local[0]);
				assertTrue(p.valid()); local[0] = false; session.progress(.6f);
				assertFalse(session.commit(() -> closes[0]++)); assertEquals(0, closes[0]); s.assertRestored();
			});
		} finally { if (f[0] != null) f[0].image.recycle(); }
	}
	@Test public void videoOpeningHandoffKeepsThePlayerChildAndAcceptedCloseRunsOnce() {
		Fixture[] f = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; Object item = new Object(); int[] closes = {0};
				GalleryMotionController motion = new GalleryMotionController(s.root); motion.setMediaChrome(new View[] {s.controls});
				GalleryMotionController.Scene entrance = motion.new Scene(s.photo, s.chrome, false, null, item, s.surface);
				motion.install(entrance); entrance.image = s.photo.getGalleryImageBounds();
				entrance.thumbnailTarget = new PhotoTransitionGeometry.Target(s.thumbnail, s.photo);
				entrance.thumbnail = new android.graphics.RectF(entrance.thumbnailTarget.frame);
				entrance.crop = entrance.thumbnailTarget.crop; entrance.apply(.3f);
				float scale = s.surface.getScaleX(), x = s.surface.getTranslationX(), y = s.surface.getTranslationY();
				GalleryMotionController.OpeningFrame opening = motion.takeOpening(s.photo, item); assertNotNull(opening);
				PredictivePhotoPresentation p = new PredictivePhotoPresentation(s.root, s.photo, s.chrome,
						new PredictivePhotoPresentation.Target(s.thumbnail, s.photo), null, null, () -> s.owned,
						s.surface, new View[] {s.controls}, item);
				p.startFromOpening(opening); PredictiveTransition session = new PredictiveTransition(s.root, p, opening.fraction);
				p.setFinishSession(session::finish); s.assertChildUnchanged();
				assertEquals(scale, s.surface.getScaleX(), .001f); assertEquals(x, s.surface.getTranslationX(), .001f);
				assertEquals(y, s.surface.getTranslationY(), .001f); assertTrue(session.commit(() -> {s.assertRestored(); closes[0]++;}));
				session.finish(); session.finish(); assertEquals(1, closes[0]); s.assertRestored();
			});
		} finally { if (f[0] != null) f[0].image.recycle(); }
	}
}
