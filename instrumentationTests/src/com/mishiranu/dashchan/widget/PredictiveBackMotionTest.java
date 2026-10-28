package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PredictiveBackMotionTest {
	private View surface() {
		View view = new View(InstrumentationRegistry.getInstrumentation().getTargetContext());
		view.layout(0, 0, 200, 300);
		view.setTranslationX(7f); view.setScaleX(.8f); view.setScaleY(.9f);
		view.setPivotX(11f); view.setPivotY(13f); view.setAlpha(.6f);
		return view;
	}
	private void assertRestored(View view) {
		assertEquals(7f, view.getTranslationX(), 0f); assertEquals(.8f, view.getScaleX(), 0f);
		assertEquals(.9f, view.getScaleY(), 0f); assertEquals(11f, view.getPivotX(), 0f);
		assertEquals(13f, view.getPivotY(), 0f); assertEquals(.6f, view.getAlpha(), 0f);
	}

	@Test public void cancelRestoresOriginalPropertiesForBothEdgesAndNeverDismisses() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
			for (float direction : new float[] {-1f, 1f}) {
				View view = surface(); PredictiveBackMotion motion = new PredictiveBackMotion();
				assertTrue(motion.start(view, direction, 40f, .08f)); motion.progress(.5f);
				assertEquals(7f + direction * 20f, view.getTranslationX(), .001f);
				assertEquals(.6f, view.getAlpha(), 0f);
				motion.cancel(); assertRestored(view); assertFalse(motion.isBusy());
				assertFalse(motion.commit(() -> fail("Cancelled gesture must not dismiss")));
			}
		});
	}

	@Test public void commitRestoresBeforeDismissAndCannotRunTwice() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
			View view = surface(); PredictiveBackMotion motion = new PredictiveBackMotion(); int[] dismiss = {0};
			assertTrue(motion.start(view, 1f, 40f, .08f)); motion.progress(.5f);
			assertTrue(motion.commit(() -> { assertRestored(view); dismiss[0]++; }));
			assertFalse(motion.commit(() -> dismiss[0]++)); assertEquals(1, dismiss[0]);
			assertFalse(motion.isBusy());
		});
	}

	@Test public void resizeAndReplacementRecoverWithoutLeavingTransformedViews() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
			View first = surface(), second = surface(); PredictiveBackMotion motion = new PredictiveBackMotion();
			assertTrue(motion.start(first, 1f, 40f, .08f)); motion.progress(.6f);
			assertTrue(motion.start(second, -1f, 40f, .08f)); assertRestored(first);
			motion.progress(.5f); second.layout(0, 0, 220, 300);
			assertFalse(motion.isBusy()); assertRestored(second);
		});
	}

	@Test public void resizePreservesAnImplicitLayoutDependentPivot() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
			View view = new View(InstrumentationRegistry.getInstrumentation().getTargetContext());
			view.layout(0, 0, 200, 300);
			PredictiveBackMotion motion = new PredictiveBackMotion();
			assertTrue(motion.start(view, 1f, 40f, .08f)); motion.progress(.5f);
			view.layout(0, 0, 220, 320);
			assertFalse(motion.isBusy());
			assertEquals(110f, view.getPivotX(), 0f); assertEquals(160f, view.getPivotY(), 0f);
		});
	}
}
