package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import org.junit.Assume;
import org.junit.Test;

/** Prepared only: exercises interruption of a real, attached animation, not an immediate fallback. */
public class PredictiveBackInterruptionTest {
	@Test public void confirmedBackSurvivesResizeDetachOrExplicitFinishExactlyOnce() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
				FrameLayout host = new FrameLayout(activity); activity.setContentView(host);
				for (int interruption = 0; interruption < 3; interruption++) {
					View view = new View(activity); host.addView(view, new FrameLayout.LayoutParams(200, 300));
					view.layout(0, 0, 200, 300); assertTrue(view.isAttachedToWindow());
					view.setTranslationX(7f); view.setScaleX(.8f); view.setScaleY(.9f); view.setAlpha(.6f);
					PredictiveBackMotion motion = new PredictiveBackMotion(); int[] calls = {0};
					assertTrue(motion.start(view, 1f, 40f, .08f)); motion.progress(.5f);
					assertTrue(motion.commit(() -> {
						calls[0]++;
						assertEquals(7f, view.getTranslationX(), 0f); assertEquals(.8f, view.getScaleX(), 0f);
						assertEquals(.9f, view.getScaleY(), 0f); assertEquals(.6f, view.getAlpha(), 0f);
					}));
					assertTrue(motion.isCommitting()); assertEquals(0, calls[0]);
					assertFalse(motion.commit(() -> fail("Repeated Back must not dispatch again")));
					if (interruption == 0) view.layout(0, 0, 240, 300);
					else if (interruption == 1) host.removeView(view);
					else motion.finish(); // Focus/policy/lifecycle interruption uses this path.
					assertEquals(1, calls[0]); assertFalse(motion.isBusy());
					motion.finish(); motion.cancel(); assertEquals(1, calls[0]);
					host.removeView(view);
				}
			});
		}
	}
}
