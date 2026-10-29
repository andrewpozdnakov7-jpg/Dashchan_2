package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.widget.FrameLayout;
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
	private static void prepareNow(PredictiveBackController controller) {
		try { java.lang.reflect.Method m = controller.getClass().getDeclaredMethod("prepareFrame"); m.setAccessible(true); m.invoke(controller); }
		catch (ReflectiveOperationException error) { throw new AssertionError(error); }
	}
	@Test public void preparedStartAndProgressDoNotCallCaptureProviderAndInvalidatedResultIsReleased() {
		Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 34 && ValueAnimator.areAnimatorsEnabled());
		String key = com.mishiranu.dashchan.content.Preferences.KEY_PREDICTIVE_BACK;
		Object old = com.mishiranu.dashchan.content.Preferences.PREFERENCES.getAll().get(key);
		FrameLayout[] host = {null}; PredictiveBackController[] controller = {null}; int[] captures = {0};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> {
				com.mishiranu.dashchan.content.Preferences.PREFERENCES.edit().put(key, true).close();
				host[0] = new FrameLayout(a); host[0].setBackgroundColor(android.graphics.Color.RED); a.setContentView(host[0]);
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				controller[0] = new PredictiveBackController(host[0], () -> {}, view -> { captures[0]++; return PredictiveBackController.capture(view); });
				Object destination = new Object(); controller[0].remember(destination);
				controller[0].prepare(new Object()); prepareNow(controller[0]); int before = captures[0];
				assertTrue(controller[0].start(destination, true));
				controller[0].progress(.2f); controller[0].progress(.8f); controller[0].progress(.3f);
				assertEquals(before, captures[0]); controller[0].clear();
				Bitmap[] invalidated = {null}; PredictiveBackController[] late = {null};
				late[0] = new PredictiveBackController(host[0], () -> {}, view -> {
					late[0].clear(); invalidated[0] = image(); return invalidated[0];
				});
				late[0].prepare(new Object()); prepareNow(late[0]);
				assertTrue(invalidated[0].isRecycled()); assertFalse(late[0].isBusy()); late[0].clear();
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
