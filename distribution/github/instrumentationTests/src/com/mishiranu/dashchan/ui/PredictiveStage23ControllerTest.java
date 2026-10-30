package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.widget.FrameLayout;
import com.mishiranu.dashchan.widget.BackPreviewBudget;
import com.mishiranu.dashchan.widget.PredictiveBackLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.widget.StandardContentTestActivity;
import org.junit.Assume;
import org.junit.Test;

/** Attached-host regressions. Pausing a real Animator isolates readiness timeout from animation duration. */
public class PredictiveStage23ControllerTest {
	private static Object field(Object owner, String name) {
		try { java.lang.reflect.Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
		catch (ReflectiveOperationException error) { throw new AssertionError(error); }
	}
	private static Bitmap image() { return Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888); }
	@Test public void liveStartProgressAndRedrawDoNotCaptureAndConfirmationFreezesExactlyOnce() {
		Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 34 && ValueAnimator.areAnimatorsEnabled());
		String key = com.mishiranu.dashchan.content.Preferences.KEY_PREDICTIVE_BACK;
		Object old = com.mishiranu.dashchan.content.Preferences.PREFERENCES.getAll().get(key);
		FrameLayout[] host = {null}; PredictiveBackController[] controller = {null}; int[] captures = {0};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> {
				com.mishiranu.dashchan.content.Preferences.PREFERENCES.edit().put(key, true).close();
				host[0] = new PredictiveBackLayout(a); host[0].setBackgroundColor(android.graphics.Color.RED); a.setContentView(host[0]);
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				controller[0] = new PredictiveBackController(host[0], () -> {}, view -> { captures[0]++; return PredictiveBackController.capture(view); });
				Object destination = new Object(); controller[0].remember(destination);
				host[0].setBackgroundColor(android.graphics.Color.GREEN);
				controller[0].prepare(new Object()); int before = captures[0];
				host[0].invalidate(); // A new draw must not invalidate the gesture's source pixels.
				assertTrue(controller[0].start(destination, true));
				controller[0].progress(.2f); controller[0].progress(.8f); controller[0].progress(.3f);
				Bitmap drawing = Bitmap.createBitmap(host[0].getWidth(), host[0].getHeight(), Bitmap.Config.ARGB_8888);
				try {
					host[0].draw(new android.graphics.Canvas(drawing));
					assertEquals(android.graphics.Color.GREEN, drawing.getPixel(drawing.getWidth() / 2, drawing.getHeight() / 2));
				} finally { drawing.recycle(); }
				assertEquals(before, captures[0]);
				int[] navigations = {0};
				assertTrue(controller[0].commit(() -> navigations[0]++));
				Bitmap frozen = (Bitmap) field(controller[0], "outgoing");
				assertNotNull(frozen); assertEquals(android.graphics.Color.GREEN, frozen.getPixel(0, 0));
				assertEquals(before + 1, captures[0]); assertEquals(1, navigations[0]);
				assertFalse(controller[0].commit(() -> navigations[0]++));
				controller[0].finish(); controller[0].close(); assertEquals(1, navigations[0]);
				Bitmap[] invalidated = {null}; PredictiveBackController[] late = {null};
				late[0] = new PredictiveBackController(host[0], () -> {}, view -> {
					late[0].clear(); invalidated[0] = image(); return invalidated[0];
				});
				Object source = new Object(); late[0].prepare(source); late[0].remember(source);
				assertTrue(invalidated[0].isRecycled()); assertFalse(late[0].isBusy()); late[0].close();
			});
		} finally {
			try (com.mishiranu.dashchan.util.SharedPreferences.Editor editor = com.mishiranu.dashchan.content.Preferences.PREFERENCES.edit()) {
				if (old instanceof Boolean) editor.put(key, (Boolean) old); else editor.remove(key);
			}
		}
	}
	@Test public void twentyPagesAreRetainedAndMemoryTrimCannotRecycleTheActiveDestination() {
		Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 34 && ValueAnimator.areAnimatorsEnabled());
		String key = com.mishiranu.dashchan.content.Preferences.KEY_PREDICTIVE_BACK;
		Object old = com.mishiranu.dashchan.content.Preferences.PREFERENCES.getAll().get(key);
		PredictiveBackLayout[] host = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> {
				com.mishiranu.dashchan.content.Preferences.PREFERENCES.edit().put(key, true).close();
				host[0] = new PredictiveBackLayout(a); a.setContentView(host[0]);
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				long before = BackPreviewBudget.usedBytes();
				android.view.View small = new android.view.View(a); small.layout(0, 0, 20, 40);
				PredictiveBackController controller = new PredictiveBackController(host[0], () -> {},
						view -> BackPreviewBudget.capture(small, 3200, android.graphics.Color.BLUE));
				try {
					Object[] pages = new Object[20];
					for (int i = 0; i < pages.length; i++) { pages[i] = new Object(); controller.prepare(pages[i]); controller.remember(pages[i]); }
					assertEquals(20, ((java.util.Map<?, ?>) field(controller, "previews")).size());
					assertTrue(controller.start(pages[0], true)); controller.finish();
					assertTrue(((BackPreviewBudget.Cache) field(controller, "cache")).evictOldest());
					java.util.Map<?, ?> cached = (java.util.Map<?, ?>) field(controller, "previews");
					assertTrue(cached.containsKey(pages[0])); assertFalse(cached.containsKey(pages[1]));
					controller.prepare(new Object(), pages[2]);
					assertTrue(((BackPreviewBudget.Cache) field(controller, "cache")).evictOldest());
					assertTrue(cached.containsKey(pages[2])); assertFalse(cached.containsKey(pages[3]));
					controller.prepare(new Object()); assertTrue(controller.start(pages[19], true));
					Bitmap active = (Bitmap) field(controller, "incoming"); assertNotNull(active);
					controller.progress(.5f); controller.trimMemory();
					assertTrue(controller.isTracking()); assertFalse(active.isRecycled());
					assertEquals(0, ((java.util.Map<?, ?>) field(controller, "previews")).size());
					controller.finish(); assertTrue(active.isRecycled());
				} finally { controller.close(); }
				assertEquals(before, BackPreviewBudget.usedBytes());
			});
		} finally {
			try (com.mishiranu.dashchan.util.SharedPreferences.Editor editor = com.mishiranu.dashchan.content.Preferences.PREFERENCES.edit()) {
				if (old instanceof Boolean) editor.put(key, (Boolean) old); else editor.remove(key);
			}
		}
	}
	@Test public void missingSnapshotUsesLiveFallbackAndCancellationDoesNotCaptureOrMutateViews() {
		Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 34 && ValueAnimator.areAnimatorsEnabled());
		String key = com.mishiranu.dashchan.content.Preferences.KEY_PREDICTIVE_BACK;
		Object old = com.mishiranu.dashchan.content.Preferences.PREFERENCES.getAll().get(key);
		PredictiveBackLayout[] host = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> {
				com.mishiranu.dashchan.content.Preferences.PREFERENCES.edit().put(key, true).close();
				host[0] = new PredictiveBackLayout(a); a.setContentView(host[0]);
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				int[] captures = {0};
				PredictiveBackController controller = new PredictiveBackController(host[0], () -> {}, view -> { captures[0]++; return image(); });
				host[0].setTranslationX(7f); host[0].setScaleX(.9f); host[0].setAlpha(.8f);
				try {
					controller.prepare(new Object()); assertTrue(controller.start(new Object(), false));
					assertNull(field(controller, "incoming")); controller.progress(.7f); controller.cancel();
					controller.finish(); assertFalse(controller.isBusy()); assertEquals(0, captures[0]);
					assertEquals(7f, host[0].getTranslationX(), 0f); assertEquals(.9f, host[0].getScaleX(), 0f);
					assertEquals(.8f, host[0].getAlpha(), 0f);
					assertTrue(controller.start(new Object(), true)); controller.finish(); assertEquals(0, captures[0]);
				} finally { controller.close(); }
			});
		} finally {
			try (com.mishiranu.dashchan.util.SharedPreferences.Editor editor = com.mishiranu.dashchan.content.Preferences.PREFERENCES.edit()) {
				if (old instanceof Boolean) editor.put(key, (Boolean) old); else editor.remove(key);
			}
		}
	}
	@Test public void readyDestinationIsNotTimedOutWhileItsCompletionAnimatorIsStillRunning() {
		Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
		FrameLayout[] host = {null}; PredictiveBackController[] controller = {null}; Bitmap previous = image(); int[] actions = {0};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> { host[0] = new FrameLayout(a); a.setContentView(host[0]); });
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				controller[0] = new PredictiveBackController(host[0]); controller[0].install(image(), previous, true);
				assertTrue(controller[0].commit(() -> actions[0]++));
				((ValueAnimator) field(controller[0], "animator")).pause();
				controller[0].destinationCommitted(null); host[0].getViewTreeObserver().dispatchOnPreDraw();
			});
			SystemClock.sleep(850);
			scenario.onActivity(a -> {
				assertTrue("Readiness watchdog must not end a valid slow completion", controller[0].isCommitting());
				assertEquals(1, actions[0]); assertFalse(controller[0].commit(() -> actions[0]++));
				((ValueAnimator) field(controller[0], "animator")).end(); assertFalse(controller[0].isBusy());
				assertEquals(1, actions[0]); controller[0].clear();
			});
		} finally { if (!previous.isRecycled()) previous.recycle(); }
	}
	@Test public void missingDestinationTimesOutAndLateOldPredrawCannotReleaseAnotherSession() {
		Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
		FrameLayout[] host = {null}; PredictiveBackController[] controller = {null}; Bitmap previous = image(); int[] actions = {0};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> { host[0] = new FrameLayout(a); a.setContentView(host[0]); });
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				controller[0] = new PredictiveBackController(host[0]); controller[0].install(image(), previous, true);
				controller[0].commit(() -> actions[0]++); ((ValueAnimator) field(controller[0], "animator")).pause();
			});
			SystemClock.sleep(850);
			scenario.onActivity(a -> {
				assertFalse(controller[0].isBusy()); assertEquals(1, actions[0]);
				controller[0].install(image(), previous, false); controller[0].destinationCommitted(null);
				host[0].getViewTreeObserver().dispatchOnPreDraw(); assertTrue(controller[0].isTracking());
				controller[0].finish(); assertEquals(1, actions[0]);
			});
		} finally { if (!previous.isRecycled()) previous.recycle(); }
	}
}
