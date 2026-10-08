package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.animation.Animator;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import androidx.fragment.app.FragmentTransaction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.widget.CompactToolbar;
import com.mishiranu.dashchan.widget.MotionToolbarExtra;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.ThreadMotionLayout;
import org.junit.Test;
import org.junit.runner.RunWith;

/** No preference writes, network, keyboard calls or system duration changes. */
@RunWith(AndroidJUnit4.class)
public class ScreenMotionControllerTest {
	private static final class Fixture {
		final android.content.Context context = ThemeEngine.attach(
				InstrumentationRegistry.getInstrumentation().getTargetContext());
		final ThreadMotionLayout host = new ThreadMotionLayout(context, null);
		final FrameLayout chromeHost = new FrameLayout(context);
		final View toolbar = new View(context), extra = new View(context), root = new View(context);
		final ScreenMotionController controller = new ScreenMotionController(host, chromeHost, toolbar, extra);
		final Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
		final ScreenMotionController.Session session;
		int touches;
		Fixture() {
			host.addView(root, new FrameLayout.LayoutParams(100, 100));
			chromeHost.addView(toolbar); chromeHost.addView(extra);
			host.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
			host.layout(0, 0, 100, 100); root.layout(0, 0, 100, 100); chromeHost.layout(0, 0, 100, 100);
			root.setAlpha(.7f); root.setTranslationX(3f); root.setTranslationY(4f);
			root.setScaleX(.9f); root.setScaleY(.8f);
			root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
			toolbar.setAlpha(.8f); extra.setAlpha(.6f);
			root.setOnTouchListener((view, event) -> { touches++; return true; });
			session = controller.new Session(ScreenMotionSpec.FORWARD, FragmentTransaction.TRANSIT_FRAGMENT_OPEN,
					360, bitmap, new RectF(0, 0, 100, 100));
			controller.install(session); session.bind(root);
		}
		void down() {
			MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 50, 50, 0);
			try { assertTrue(host.dispatchTouchEvent(event)); } finally { event.recycle(); }
		}
		void assertReleased() {
			assertTrue(bitmap.isRecycled()); assertNull(root.getTag(R.id.screen_motion_owner));
			assertNull(toolbar.getTag(R.id.screen_motion_owner)); assertNull(extra.getTag(R.id.screen_motion_owner));
			assertNull(host.getTag(R.id.screen_motion_owner));
			assertEquals(.7f, root.getAlpha(), 0f); assertEquals(3f, root.getTranslationX(), 0f);
			assertEquals(4f, root.getTranslationY(), 0f); assertEquals(.9f, root.getScaleX(), 0f);
			assertEquals(.8f, root.getScaleY(), 0f); assertEquals(.8f, toolbar.getAlpha(), 0f);
			assertEquals(.6f, extra.getAlpha(), 0f);
			assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, root.getImportantForAccessibility());
			down(); assertEquals(1, touches);
			root.getViewTreeObserver().dispatchOnPreDraw(); // A removed listener cannot acquire ownership again.
			assertNull(root.getTag(R.id.screen_motion_owner));
		}
	}
	@Test public void finishBeforeStartRestoresPropertiesChromeAccessibilityAndTouches() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture();
			try {
				assertEquals(0f, f.root.getAlpha(), 0f); f.down(); assertEquals(0, f.touches);
				f.session.createAnimator(null); f.controller.finish(); f.assertReleased();
			} finally { f.controller.finish(); }
		});
	}
	@Test public void hostResizeReleasesAnUnstartedSessionAndLateAnimatorIsHarmless() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture();
			try {
				f.host.layout(0, 0, 120, 100); f.assertReleased();
				Animator late = f.session.createAnimator(null); late.start(); late.end();
				assertEquals(.7f, f.root.getAlpha(), 0f);
			} finally { f.controller.finish(); }
		});
	}
	@Test public void replacementOwnerIsNotRestoredOrUnblockedByAnOldSession() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture();
			Bitmap nextImage = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
			ScreenMotionController.Session next = f.controller.new Session(ScreenMotionSpec.BACK,
					FragmentTransaction.TRANSIT_FRAGMENT_CLOSE, 320, nextImage, new RectF(0, 0, 100, 100));
			try {
				f.controller.install(next); next.bind(f.root); f.session.finish();
				assertSame(next, f.root.getTag(R.id.screen_motion_owner));
				assertSame(next, f.toolbar.getTag(R.id.screen_motion_owner));
				f.down(); assertEquals(0, f.touches); assertTrue(f.bitmap.isRecycled());
			} finally { f.controller.finish(); }
			assertTrue(nextImage.isRecycled()); assertEquals(.7f, f.root.getAlpha(), 0f);
		});
	}
	@Test public void foreignOwnershipIsPreservedWhenASessionFinishes() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(); Object owner = new Object();
			f.root.setTag(R.id.screen_motion_owner, owner); f.root.setAlpha(.25f);
			f.toolbar.setTag(R.id.screen_motion_owner, owner); f.toolbar.setAlpha(.35f);
			f.controller.finish();
			assertSame(owner, f.root.getTag(R.id.screen_motion_owner)); assertEquals(.25f, f.root.getAlpha(), 0f);
			assertSame(owner, f.toolbar.getTag(R.id.screen_motion_owner)); assertEquals(.35f, f.toolbar.getAlpha(), 0f);
			assertTrue(f.bitmap.isRecycled());
		});
	}
	@Test public void snapshotBudgetFailureAndExternalSurfaceAreSafe() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(); f.controller.finish();
			View view = new View(f.context); view.layout(0, 0, 100, 200); view.setBackgroundColor(Color.BLUE);
			Bitmap image = ScreenMotionController.capture(view, 5000, Color.RED);
			assertNotNull(image);
			try {
				assertTrue((long) image.getWidth() * image.getHeight() <= 5000);
				assertEquals(Color.BLUE, image.getPixel(0, 0));
			} finally { image.recycle(); }
			assertNull(ScreenMotionController.capture(view, 0, Color.RED));
			View failing = new View(f.context) { @Override public void draw(android.graphics.Canvas canvas) {
				throw new IllegalStateException("Test capture failure");
			} };
			failing.layout(0, 0, 10, 10); assertNull(ScreenMotionController.capture(failing, 100, Color.RED));
			FrameLayout group = new FrameLayout(f.context); group.addView(new SurfaceView(f.context));
			assertTrue(ScreenMotionController.externalSurface(group)); assertFalse(ScreenMotionController.externalSurface(view));
		});
	}
	@Test public void hiddenToolbarAndFormattingChildrenCannotConsumeATapAndKeepEnabledState() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(); f.controller.finish();
			CompactToolbar toolbar = new CompactToolbar(f.context, null);
			MotionToolbarExtra extra = new MotionToolbarExtra(f.context, null);
			int[] touches = {0};
			toolbar.setOnTouchListener((view, event) -> { touches[0]++; return true; });
			extra.setOnTouchListener((view, event) -> { touches[0]++; return true; });
			Object owner = new Object(); toolbar.setTag(R.id.screen_motion_owner, owner); extra.setTag(R.id.screen_motion_owner, owner);
			MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
			try {
				assertTrue(toolbar.dispatchTouchEvent(event)); assertTrue(extra.dispatchTouchEvent(event));
				assertEquals(0, touches[0]); assertTrue(toolbar.isEnabled()); assertTrue(extra.isEnabled());
				toolbar.setTag(R.id.screen_motion_owner, null); extra.setTag(R.id.screen_motion_owner, null);
				toolbar.dispatchTouchEvent(event); extra.dispatchTouchEvent(event); assertEquals(2, touches[0]);
			} finally { event.recycle(); }
		});
	}
}
