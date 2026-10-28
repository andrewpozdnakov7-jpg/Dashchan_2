package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.widget.ThemeEngine;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Pixel and lifecycle contracts; no network, settings writes, or real fragment navigation. */
@RunWith(AndroidJUnit4.class)
public class PredictiveBackControllerTest {
	private static class Fixture {
		final android.content.Context context = ThemeEngine.attach(
				InstrumentationRegistry.getInstrumentation().getTargetContext());
		final FrameLayout host = new FrameLayout(context);
		final View child = new View(context);
		final PredictiveBackController controller = new PredictiveBackController(host);
		final Bitmap current = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
		final Bitmap previous = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
		Fixture(boolean fromLeft) {
			host.addView(child); host.layout(0, 0, 100, 200); child.layout(0, 0, 100, 200);
			child.setAlpha(.7f); child.setTranslationX(3f); child.setScaleX(.9f);
			current.eraseColor(Color.RED); previous.eraseColor(Color.BLUE);
			controller.install(current, previous, fromLeft);
		}
		int pixel(int x) {
			Bitmap frame = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
			try { host.draw(new Canvas(frame)); return frame.getPixel(x, 100); }
			finally { frame.recycle(); }
		}
		void close() { controller.clear(); previous.recycle(); }
	}

	@Test public void bothEdgesRevealTheDestinationWithoutMutatingLiveViews() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean fromLeft : new boolean[] {true, false}) {
				Fixture f = new Fixture(fromLeft);
				try {
					assertEquals(Color.RED, f.pixel(50));
					f.controller.progress(.5f);
					int revealed = f.pixel(fromLeft ? 5 : 95), covered = f.pixel(fromLeft ? 80 : 20);
					assertTrue(Color.blue(revealed) > Color.red(revealed));
					assertTrue(Color.red(covered) > Color.blue(covered));
					assertEquals(.7f, f.child.getAlpha(), 0f); assertEquals(3f, f.child.getTranslationX(), 0f);
					assertEquals(.9f, f.child.getScaleX(), 0f);
					f.controller.cancel(); // Unattached fixture recovers synchronously.
					assertFalse(f.controller.isBusy()); assertTrue(f.current.isRecycled());
					assertFalse(f.previous.isRecycled());
				} finally { f.close(); }
			}
		});
	}

	@Test public void commitNavigatesOnceAndKeepsTheCoverUntilDestinationPreDraw() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(true); int[] navigation = {0};
			try {
				f.controller.progress(.4f);
				assertTrue(f.controller.commit(() -> navigation[0]++));
				assertEquals(1, navigation[0]); assertTrue(f.controller.isCommitting());
				assertFalse(f.controller.commit(() -> navigation[0]++)); assertEquals(1, navigation[0]);
				assertFalse(f.current.isRecycled());
				f.controller.destinationCommitted(new Object()); f.host.getViewTreeObserver().dispatchOnPreDraw();
				assertTrue("An unrelated destination must not release the cover", f.controller.isCommitting());
				f.controller.destinationCommitted(null); f.host.getViewTreeObserver().dispatchOnPreDraw();
				assertFalse(f.controller.isBusy()); assertTrue(f.current.isRecycled());
				assertFalse(f.previous.isRecycled());
			} finally { f.close(); }
		});
	}

	@Test public void resizeRemovesTheCoverAndItsLateDestinationListener() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(true);
			try {
				f.controller.commit(() -> {}); f.controller.destinationCommitted(null);
				f.host.layout(0, 0, 120, 200);
				assertFalse(f.controller.isBusy()); assertTrue(f.current.isRecycled());
				f.host.getViewTreeObserver().dispatchOnPreDraw(); assertFalse(f.controller.isCommitting());
			} finally { f.close(); }
		});
	}

	@Test public void teardownDuringNavigationCannotRestartAnOrphanAnimatorOrNavigateAgain() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(true); int[] calls = {0};
			try {
				assertTrue(f.controller.commit(() -> { calls[0]++; f.controller.clear(); }));
				assertEquals(1, calls[0]); assertFalse(f.controller.isBusy());
				f.controller.destinationCommitted(null); f.host.getViewTreeObserver().dispatchOnPreDraw();
				f.controller.finish(); assertEquals(1, calls[0]); assertTrue(f.current.isRecycled());
			} finally { f.close(); }
		});
	}

	@Test public void snapshotsAreBoundedAndVisibleExternalSurfacesUseTheFallback() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(true); f.controller.finish();
			try {
				View view = new View(f.context); view.layout(0, 0, 1080, 2400); view.setBackgroundColor(Color.BLUE);
				Bitmap image = PredictiveBackController.capture(view); assertNotNull(image);
				try { assertTrue((long) image.getWidth() * image.getHeight() <= 500_000); }
				finally { image.recycle(); }
				f.host.addView(new SurfaceView(f.context));
				assertNull(PredictiveBackController.capture(f.host));
				f.host.getChildAt(1).setVisibility(View.GONE);
				assertFalse(PredictiveBackController.externalSurface(f.host));
				assertEquals(0f, PredictiveBackController.clamp(Float.NaN), 0f);
			} finally { f.close(); }
		});
	}
}
