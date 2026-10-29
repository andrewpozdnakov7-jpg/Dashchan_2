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

/** Attached-window fixtures: frozen hidden-grid geometry and the real PhotoView draw order. */
public class PredictiveGridGeometryTest {
	@Test public void clearingGridCacheKeepsTheActivePresentationLeaseUntilTeardown() {
		Fixture[] f = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; PredictiveGridGeometry geometry = new PredictiveGridGeometry(s.grid);
				android.graphics.Bitmap grid = com.mishiranu.dashchan.widget.BackPreviewBudget.capture(s.grid,
						com.mishiranu.dashchan.widget.BackPreviewBudget.limit(a) / 4, Color.GREEN);
				assertNotNull(grid); Rect bounds = new Rect(); geometry.boundsIn(s.root, bounds);
				PredictivePhotoPresentation.Target target = geometry.target(geometry.capture(s.second), s.photo); assertNotNull(target);
				PredictiveTransition transition = new PredictiveTransition(s.root,
						new PredictivePhotoPresentation(s.root, s.photo, null, target, grid, bounds, () -> true));
				com.mishiranu.dashchan.widget.BackPreviewBudget.release(grid); assertFalse(grid.isRecycled());
				transition.progress(.8f); transition.finish(); transition.finish(); assertTrue(grid.isRecycled());
			});
		} finally { if (f[0] != null) f[0].image.recycle(); }
	}
	private static final class Fixture {
		final FrameLayout root, grid;
		final AttachmentView first, second;
		final PhotoView photo;
		final Bitmap image = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888);
		Fixture(StandardContentTestActivity activity) {
			root = new FrameLayout(activity); root.setBackgroundColor(Color.BLACK);
			grid = new FrameLayout(activity); root.addView(grid, new FrameLayout.LayoutParams(240, 360));
			first = thumbnail(activity, 16); second = thumbnail(activity, 96);
			photo = new PhotoView(activity, null); root.addView(photo, new FrameLayout.LayoutParams(240, 360));
			activity.setContentView(root);
			image.setDensity(Bitmap.DENSITY_NONE); image.eraseColor(Color.BLUE);
			photo.setImage(new BitmapDrawable(activity.getResources(), image), false, false, false);
		}
		private AttachmentView thumbnail(StandardContentTestActivity activity, int left) {
			AttachmentView result = new AttachmentView(activity, null); result.setCropEnabled(true);
			FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(48, 48);
			params.leftMargin = left; params.topMargin = 48; grid.addView(result, params); return result;
		}
	}

	@Test public void currentCellSurvivesHiddenGridButRejectsRebinding() {
		Fixture[] f = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; PredictiveGridGeometry geometry = new PredictiveGridGeometry(s.grid);
				PredictiveGridGeometry.Cell first = geometry.capture(s.first), second = geometry.capture(s.second);
				assertNotNull(first); assertNotNull(second); s.grid.setVisibility(View.GONE);
				PredictivePhotoPresentation.Target aTarget = geometry.target(first, s.photo), bTarget = geometry.target(second, s.photo);
				assertNotNull(aTarget); assertNotNull(bTarget); assertTrue(bTarget.valid(s.photo));
				assertEquals(80f, bTarget.frame.left - aTarget.frame.left, 0f);
				s.second.resetImage("another binding"); assertFalse(bTarget.valid(s.photo));
				assertNull(geometry.target(second, s.photo)); assertTrue(aTarget.valid(s.photo));
			});
		} finally { if (f[0] != null) f[0].image.recycle(); }
	}

	@Test public void changedCellLayoutRejectsCommitWithoutNavigation() {
		Fixture[] f = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; PredictiveGridGeometry geometry = new PredictiveGridGeometry(s.grid);
				PredictiveGridGeometry.Cell cell = geometry.capture(s.first); assertNotNull(cell); s.grid.setVisibility(View.GONE);
				PredictivePhotoPresentation.Target target = geometry.target(cell, s.photo); assertNotNull(target);
				PredictiveTransition transition = new PredictiveTransition(s.root,
						new PredictivePhotoPresentation(s.root, s.photo, null, target, null, null, () -> true));
				transition.progress(.4f);
				s.first.layout(s.first.getLeft() + 1, s.first.getTop(), s.first.getRight() + 1, s.first.getBottom());
				assertFalse(transition.commit(() -> fail("Stale geometry must not navigate")));
				assertNull(s.photo.getTag(R.id.gallery_motion_owner)); assertNull(s.root.getTag(R.id.gallery_motion_owner));
				assertEquals(View.GONE, s.grid.getVisibility());
			});
		} finally { if (f[0] != null) f[0].image.recycle(); }
	}

	@Test public void shrinkingPhotoRemainsAboveGridAndOnlyCommitChangesMode() {
		Fixture[] f = {null}; Bitmap grid = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888); grid.eraseColor(Color.GREEN);
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> f[0] = new Fixture(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				Fixture s = f[0]; PredictiveGridGeometry geometry = new PredictiveGridGeometry(s.grid);
				PredictiveGridGeometry.Cell cell = geometry.capture(s.second); assertNotNull(cell);
				Rect bounds = new Rect(); geometry.boundsIn(s.root, bounds); s.grid.setVisibility(View.GONE);
				PredictivePhotoPresentation.Target target = geometry.target(cell, s.photo); assertNotNull(target);
				int[] modeChanges = {0};
				PredictiveTransition transition = new PredictiveTransition(s.root,
						new PredictivePhotoPresentation(s.root, s.photo, null, target, grid, bounds, () -> true));
				transition.progress(1f); assertEquals(0, modeChanges[0]);
				Bitmap pixels = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888);
				try {
					s.photo.draw(new Canvas(pixels));
					assertEquals(Color.BLUE, pixels.getPixel((int) target.frame.centerX(), (int) target.frame.centerY()));
					assertEquals(Color.GREEN, pixels.getPixel(12, 300));
				} finally { pixels.recycle(); }
				assertTrue(transition.commit(() -> modeChanges[0]++)); transition.finish(); transition.finish();
				assertEquals(1, modeChanges[0]); assertNull(s.photo.getTag(R.id.gallery_motion_owner));
			});
		} finally { grid.recycle(); if (f[0] != null) f[0].image.recycle(); }
	}
}
