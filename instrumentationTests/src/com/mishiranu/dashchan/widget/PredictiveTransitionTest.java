package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** State/action tests, prepared only: run after explicit build/device authorization. */
public class PredictiveTransitionTest {
	private static class Surface implements PredictiveTransition.Presentation {
		boolean valid = true; float position = .37f; int restores;
		@Override public boolean valid() { return valid; }
		@Override public void apply(float value) { position = .37f + value; }
		@Override public void restore() { position = .37f; restores++; }
	}
	@Test public void reversalAndCancellationRestoreWithoutAcceptingAnAction() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View host = new View(InstrumentationRegistry.getInstrumentation().getTargetContext()); Surface surface = new Surface();
			PredictiveTransition transition = new PredictiveTransition(host, surface);
			transition.progress(.8f); assertEquals(1.17f, surface.position, .001f);
			transition.progress(.2f); assertEquals(.57f, surface.position, .001f);
			transition.progress(Float.NaN); assertEquals(.37f, surface.position, .001f);
			transition.progress(Float.POSITIVE_INFINITY); assertEquals(1.37f, surface.position, .001f);
			transition.cancel(); transition.finish();
			assertFalse(transition.isBusy()); assertEquals(1, surface.restores); assertEquals(.37f, surface.position, 0f);
			assertFalse(transition.commit(() -> fail("No action after cancellation")));
		});
	}
	@Test public void invalidOwnerRejectsCommitAndTheNextIndependentSessionWorks() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View host = new View(InstrumentationRegistry.getInstrumentation().getTargetContext()); Surface surface = new Surface();
			int[] actions = {0}; PredictiveTransition first = new PredictiveTransition(host, surface);
			first.progress(.5f); surface.valid = false;
			assertFalse(first.commit(() -> actions[0]++)); assertEquals(0, actions[0]);
			surface.valid = true; PredictiveTransition next = new PredictiveTransition(host, surface);
			next.progress(.3f); assertTrue(next.commit(() -> { assertEquals(.37f, surface.position, 0f); actions[0]++; }));
			next.finish(); assertFalse(next.commit(() -> actions[0]++)); assertEquals(1, actions[0]);
		});
	}
	@Test public void adoptedOpeningPoseTracksRemainingDistanceAndCancelRecoversFullyOpen() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View host = new View(InstrumentationRegistry.getInstrumentation().getTargetContext()); Surface surface = new Surface();
			PredictiveTransition transition = new PredictiveTransition(host, surface, .6f);
			assertEquals(.6f, transition.getFraction(), .001f); assertEquals(.97f, surface.position, .001f);
			transition.progress(0f); assertEquals(.6f, transition.getFraction(), .001f);
			transition.progress(.5f); assertEquals(.8f, transition.getFraction(), .001f);
			transition.progress(.2f); assertEquals(.68f, transition.getFraction(), .001f);
			transition.cancel(); transition.finish(); assertEquals(1, surface.restores);
			assertEquals(.37f, surface.position, 0f); assertFalse(transition.isBusy());
			assertFalse(transition.commit(() -> fail("Cancelled entrance must not navigate")));
		});
	}
	@Test public void commitHasItsOwnCompletionClockAndRunsOnlyOnceEvenAfterALongGesture() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View host = new View(InstrumentationRegistry.getInstrumentation().getTargetContext());
			float[] completion = {-1f}; int[] calls = {0};
			Surface surface = new Surface() {
				@Override public void applyCommit(float fraction, float progress) { completion[0] = progress; apply(fraction); }
			};
			PredictiveTransition transition = new PredictiveTransition(host, surface);
			transition.progress(.99f); assertEquals(-1f, completion[0], 0f);
			assertTrue(transition.commit(() -> calls[0]++));
			// Unattached fixture completes synchronously, through the same end-pose callback.
			assertEquals(1f, completion[0], 0f); assertEquals(1, calls[0]);
			transition.finish(); assertFalse(transition.commit(() -> calls[0]++)); assertEquals(1, calls[0]);
		});
	}
	@Test public void cancellingDoesNotStartTheCompletionFadeClock() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View host = new View(InstrumentationRegistry.getInstrumentation().getTargetContext());
			Surface surface = new Surface() {
				@Override public void applyCommit(float fraction, float progress) { fail("Cancellation must not fade through"); }
			};
			PredictiveTransition transition = new PredictiveTransition(host, surface);
			transition.progress(.8f); transition.cancel(); transition.finish();
			assertEquals(1, surface.restores); assertEquals(.37f, surface.position, 0f);
		});
	}
}
