package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.animation.Animator;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.navigator.PageFragment;
import com.mishiranu.dashchan.widget.ThreadMotionLayout;
import com.mishiranu.dashchan.widget.ThemeEngine;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real session cleanup; no preference writes, network, page loading or animation-scale changes. */
@RunWith(AndroidJUnit4.class)
public class ThreadMotionControllerTest {
	@Test public void contentCaptureStartsAtViewportRatherThanReintroducingToolbarPadding() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View root = new View(ThemeEngine.attach(
					InstrumentationRegistry.getInstrumentation().getTargetContext()));
			root.layout(0, 0, 80, 100);
			Bitmap bitmap = ThreadMotionController.captureContent(root, new Rect(0, 20, 80, 100), 10_000,
					canvas -> {
						Paint paint = new Paint();
						paint.setColor(Color.RED); canvas.drawRect(0, 0, 80, 20, paint);
						paint.setColor(Color.BLUE); canvas.drawRect(0, 20, 80, 100, paint);
					});
			assertNotNull(bitmap);
			try {
				assertEquals(80, bitmap.getWidth()); assertEquals(80, bitmap.getHeight());
				assertEquals(Color.BLUE, bitmap.getPixel(0, 0));
				assertEquals(Color.BLUE, bitmap.getPixel(79, 79));
				assertEquals(1f, root.getAlpha(), 0f);
			} finally { bitmap.recycle(); }
		});
	}

	@Test public void contentCaptureKeepsTheExistingPixelBudget() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View root = new View(ThemeEngine.attach(
					InstrumentationRegistry.getInstrumentation().getTargetContext()));
			Bitmap bitmap = ThreadMotionController.captureContent(root, new Rect(0, 20, 80, 100), 1_600,
					canvas -> canvas.drawColor(Color.BLUE));
			assertNotNull(bitmap);
			try {
				assertEquals(40, bitmap.getWidth()); assertEquals(40, bitmap.getHeight());
				assertEquals(Color.BLUE, bitmap.getPixel(20, 20));
			} finally { bitmap.recycle(); }
		});
	}

	@Test public void failedOrEmptyContentCaptureReturnsNoPlaceholderScene() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View root = new View(ThemeEngine.attach(
					InstrumentationRegistry.getInstrumentation().getTargetContext()));
			assertNull(ThreadMotionController.captureContent(root, new Rect(0, 0, 10, 10), 100,
					canvas -> { throw new IllegalStateException("Test capture failure"); }));
			assertNull(ThreadMotionController.captureContent(root, new Rect(0, 0, 0, 10), 100,
					canvas -> fail("Empty capture must not draw")));
		});
	}

	private static final class Fixture {
		final ThreadMotionLayout host = new ThreadMotionLayout(
				InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
		final ThreadMotionController controller = new ThreadMotionController(host);
		final View root = new View(host.getContext());
		final Bitmap scene = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
		final Bitmap card = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888);
		final ThreadMotionController.Session session;
		int touches;

		Fixture() {
			root.setAlpha(.7f);
			root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
			root.setOnTouchListener((view, event) -> { touches++; return true; });
			host.addView(root, new FrameLayout.LayoutParams(100, 100));
			host.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
			host.layout(0, 0, 100, 100);
			// No destination layout is dispatched: these tests cover ownership before preparation.
			session = controller.new Session(new PageFragment(), null, false,
					new RectF(0, 0, 100, 100), new RectF(0, 0, 20, 20), scene, card);
			controller.install(session);
			session.bind(root);
		}

		void down() {
			MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 50, 50, 0);
			try { assertTrue(host.dispatchTouchEvent(event)); } finally { event.recycle(); }
		}
		void assertReleased() {
			assertNull(root.getTag(R.id.thread_motion_owner));
			assertEquals(.7f, root.getAlpha(), 0f);
			assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, root.getImportantForAccessibility());
			assertTrue(scene.isRecycled()); assertTrue(card.isRecycled());
			down(); assertEquals(1, touches);
			// Removed one-shot listeners must not reclaim an already released root.
			root.getViewTreeObserver().dispatchOnPreDraw();
			assertNull(root.getTag(R.id.thread_motion_owner));
			assertEquals(.7f, root.getAlpha(), 0f);
		}
	}

	@Test public void finishBeforeAnimatorStartRestoresAlphaAccessibilityAndTouches() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture();
			try {
				assertSame(f.session, f.root.getTag(R.id.thread_motion_owner));
				assertEquals(0f, f.root.getAlpha(), 0f);
				f.down(); assertEquals(0, f.touches);
				f.session.createAnimator();
				f.controller.finish(); f.controller.finish(); f.session.finish();
				f.assertReleased();
			} finally { f.controller.clear(); }
		});
	}

	@Test public void cancelStartedAnimatorUsesTheSameCleanupPath() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture();
			try {
				Animator animator = f.session.createAnimator();
				animator.start(); animator.cancel();
				f.controller.finish();
				f.assertReleased();
			} finally { f.controller.clear(); }
		});
	}

	@Test public void changingViewportFinishesPresentationWithoutMovingContent() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture();
			try {
				f.host.layout(0, 0, 100, 80);
				f.assertReleased();
				assertEquals(0f, f.root.getTranslationX(), 0f);
				assertEquals(1f, f.root.getScaleX(), 0f);
			} finally { f.controller.clear(); }
		});
	}

	@Test public void replacementReleasesOldSceneAndOldCompletionCannotUnblockNewScene() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture();
			Bitmap nextScene = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
			Bitmap nextCard = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888);
			try {
				ThreadMotionController.Session next = f.controller.new Session(new PageFragment(), null, false,
						new RectF(0, 0, 100, 100), new RectF(0, 0, 20, 20), nextScene, nextCard);
				f.controller.install(next); next.bind(f.root);
				assertTrue(f.scene.isRecycled()); assertTrue(f.card.isRecycled());
				f.session.finish();
				assertSame(next, f.root.getTag(R.id.thread_motion_owner));
				f.down(); assertEquals(0, f.touches);
				Animator animator = next.createAnimator(); animator.start(); animator.end();
				assertTrue(nextScene.isRecycled()); assertTrue(nextCard.isRecycled());
				f.assertReleased();
			} finally { f.controller.clear(); }
		});
	}

	@Test public void cleanupDoesNotClearAnotherOwnersTagAndLateAnimatorIsInstant() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture();
			try {
				Object newerOwner = new Object();
				f.root.setTag(R.id.thread_motion_owner, newerOwner);
				f.root.setAlpha(.4f);
				f.controller.clear();
				assertSame(newerOwner, f.root.getTag(R.id.thread_motion_owner));
				assertEquals(.4f, f.root.getAlpha(), 0f);
				assertTrue(f.scene.isRecycled()); assertTrue(f.card.isRecycled());
				Animator late = f.session.createAnimator();
				assertEquals(0, late.getDuration());
				late.start();
				assertSame(newerOwner, f.root.getTag(R.id.thread_motion_owner));
			} finally { f.controller.clear(); }
		});
	}
}
