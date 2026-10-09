package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Preparation/teardown only. Restores the preference and never changes the system animator scale. */
@RunWith(AndroidJUnit4.class)
public class DialogStackMotionTest {
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
					fixture.motion.finish();
					if (!fixture.bitmap.isRecycled()) fixture.bitmap.recycle();
				}
				if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(key, (Boolean) previous).close();
				else Preferences.PREFERENCES.edit().remove(key).close();
			}
		});
	}
	private static final class Fixture {
		final android.content.Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
		final FrameLayout content = new FrameLayout(context), root = new FrameLayout(context);
		final View old = new View(context), body = new View(context);
		final FrameLayout incoming = new FrameLayout(context);
		final DialogStackMotion motion = new DialogStackMotion(content, root);
		final Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
		Fixture() {
			content.addView(root); root.addView(old); root.addView(incoming); incoming.addView(body);
			root.layout(0, 0, 100, 100); incoming.layout(0, 0, 80, 80);
			body.layout(0, 0, 80, 80); incoming.setBackgroundColor(0xffffffff);
			body.setAlpha(.6f); body.setScaleX(.95f); body.setScaleY(.9f);
			body.setTranslationY(3f); body.setPivotX(4f); body.setPivotY(6f);
			old.setAlpha(.7f); incoming.setAlpha(.8f); incoming.setScaleX(.9f); incoming.setScaleY(.85f);
			incoming.setTranslationY(5f); incoming.setPivotX(7f); incoming.setPivotY(8f);
			root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
			motion.configure(new Dialog(context).getWindow(), .6f, 0);
		}
		DialogStackMotion.Snapshot snapshot() { return new DialogStackMotion.Snapshot(bitmap, new RectF(0, 0, 80, 80)); }
		void assertReleased() {
			assertTrue(bitmap.isRecycled()); assertNull(root.getTag(R.id.dialog_motion_owner));
			assertNull(old.getTag(R.id.dialog_motion_owner)); assertNull(incoming.getTag(R.id.dialog_motion_owner));
			assertNull(body.getTag(R.id.dialog_motion_owner));
			assertEquals(.6f, body.getAlpha(), 0f);
			assertEquals(.95f, body.getScaleX(), 0f); assertEquals(.9f, body.getScaleY(), 0f);
			assertEquals(3f, body.getTranslationY(), 0f);
			assertEquals(4f, body.getPivotX(), 0f); assertEquals(6f, body.getPivotY(), 0f);
			assertEquals(.7f, old.getAlpha(), 0f); assertEquals(.8f, incoming.getAlpha(), 0f);
			assertEquals(.9f, incoming.getScaleX(), 0f); assertEquals(.85f, incoming.getScaleY(), 0f);
			assertEquals(5f, incoming.getTranslationY(), 0f); assertEquals(7f, incoming.getPivotX(), 0f);
			assertEquals(8f, incoming.getPivotY(), 0f);
			assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, root.getImportantForAccessibility());
			root.getViewTreeObserver().dispatchOnPreDraw();
			assertNull(root.getTag(R.id.dialog_motion_owner));
		}
	}
	@Test public void cancelledPreparationRestoresPropertiesAndRemovesLatePreDraw() {
		fixture(f -> {
			assertTrue(f.motion.enter(f.snapshot(), f.old, f.incoming, false, false));
			MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
			try { assertTrue(DialogStackMotion.blocks(f.root, event)); } finally { event.recycle(); }
			f.motion.finish(); f.assertReleased();
		});
	}

	@Test public void nestedPushAndPopLeaveTheCardSurfaceAndScreenDimUnchanged() {
		fixture(f -> {
			int dim = ((android.graphics.drawable.ColorDrawable) f.content.getBackground()).getColor();
			android.graphics.drawable.Drawable surface = f.incoming.getBackground();
			assertTrue(f.motion.enter(f.snapshot(), f.old, f.incoming, false, false));
			assertEquals(.7f, f.old.getAlpha(), 0f);
			assertNull(f.old.getTag(R.id.dialog_motion_owner));
			assertEquals(.8f, f.incoming.getAlpha(), 0f);
			assertEquals(.9f, f.incoming.getScaleX(), 0f);
			assertEquals(5f, f.incoming.getTranslationY(), 0f);
			assertEquals(0f, f.body.getAlpha(), 0f);
			assertSame(surface, f.incoming.getBackground());
			assertEquals(dim, ((android.graphics.drawable.ColorDrawable) f.content.getBackground()).getColor());
			f.motion.finish(); f.assertReleased();
			Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
			f.motion.leave(new DialogStackMotion.Snapshot(bitmap, new RectF(0, 0, 80, 80)), f.incoming, false, null);
			assertEquals(.8f, f.incoming.getAlpha(), 0f);
			assertEquals(0f, f.body.getAlpha(), 0f);
			assertEquals(dim, ((android.graphics.drawable.ColorDrawable) f.content.getBackground()).getColor());
			f.motion.finish(); assertTrue(bitmap.isRecycled()); f.assertReleased();
		});
	}

	@Test public void nestedPushNeverTakesOpacityOwnershipOfTheLowerCard() {
		fixture(f -> {
			Object owner = new Object();
			f.old.setTag(R.id.dialog_motion_owner, owner);
			assertTrue(f.motion.enter(f.snapshot(), f.old, f.incoming, false, false));
			assertSame(owner, f.old.getTag(R.id.dialog_motion_owner));
			assertEquals(.7f, f.old.getAlpha(), 0f);
			f.motion.finish();
			assertSame(owner, f.old.getTag(R.id.dialog_motion_owner));
			assertEquals(.7f, f.old.getAlpha(), 0f);
			f.old.setTag(R.id.dialog_motion_owner, null);
			f.assertReleased();
		});
	}
	@Test public void preparingExitClosesItsWindowExactlyOnceEvenAfterRepeatedCleanup() {
		fixture(f -> {
			int[] closes = {0}; f.motion.leave(f.snapshot(), null, true, () -> closes[0]++);
			f.motion.finish(); f.motion.finish(); DialogStackMotion.lostFocus(f.root);
			assertEquals(1, closes[0]); f.assertReleased();
		});
	}
	@Test public void resizedHostDoesNotKeepAnUndrawnModalScene() {
		fixture(f -> {
			f.motion.enter(f.snapshot(), f.old, f.incoming, false, false);
			f.root.layout(0, 0, 120, 100); f.assertReleased();
		});
	}
	@Test public void foreignOwnerIsNotResetByAnOldScene() {
		fixture(f -> {
			f.motion.enter(f.snapshot(), f.old, f.incoming, false, false);
			Object next = new Object(); f.incoming.setTag(R.id.dialog_motion_owner, next); f.incoming.setAlpha(.25f);
			f.motion.finish(); assertSame(next, f.incoming.getTag(R.id.dialog_motion_owner));
			assertEquals(.25f, f.incoming.getAlpha(), 0f); assertTrue(f.bitmap.isRecycled());
		});
	}

	@Test public void missingSnapshotLeavesBothPostsAndTouchImmediatelyUsable() {
		fixture(f -> {
			assertTrue(f.motion.enter(null, f.old, f.incoming, false, false));
			assertNull(f.root.getTag(R.id.dialog_motion_owner));
			assertEquals(.7f, f.old.getAlpha(), 0f);
			assertEquals(.8f, f.incoming.getAlpha(), 0f);
			assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, f.root.getImportantForAccessibility());
		});
	}

	@Test public void restoringDoesNotPrepareAnEntranceOrRetainItsSnapshot() {
		fixture(f -> {
			assertTrue(f.motion.enter(f.snapshot(), f.old, f.incoming, false, true));
			f.assertReleased();
		});
	}

	@Test public void losingFocusReleasesTheSnapshotAndRestoresControls() {
		fixture(f -> {
			f.motion.enter(f.snapshot(), f.old, f.incoming, false, false);
			DialogStackMotion.lostFocus(f.root);
			f.assertReleased();
		});
	}

	@Test public void nextTransitionFinishesThePreviousExitBeforePreparingItsView() {
		fixture(f -> {
			int[] closes = {0};
			f.motion.leave(f.snapshot(), null, true, () -> closes[0]++);
			assertTrue(f.motion.enter(null, null, f.incoming, true, false));
			assertEquals(1, closes[0]);
			f.motion.finish();
			assertEquals(1, closes[0]);
			f.assertReleased();
		});
	}
}
