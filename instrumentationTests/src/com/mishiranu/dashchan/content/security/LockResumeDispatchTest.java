package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Synthetic launches only: no real Activity/window, profile, URI access or cleanup. ADDED / NOT RUN. */
public class LockResumeDispatchTest {
	private static final class Target extends Activity {
		int calls, failure;
		Intent received;
		Bundle options;
		@Override public void startActivity(Intent intent, Bundle options) {
			calls++; received = intent; this.options = options;
			if (failure == 1) throw new SecurityException("Fixture expired grant");
			if (failure == 2) throw new ActivityNotFoundException("Fixture missing target");
		}
	}
	@Test public void expiredGrantIsHandledWithoutRelaunchingOrThrowing() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Target target = new Target(); target.failure = 1;
			assertFalse(LockScreenForm.tryLaunchResume(target, new Intent("fixture"), null));
			assertEquals(1, target.calls);
		});
	}
	@Test public void unavailableActivityIsHandledTheSameWay() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Target target = new Target(); target.failure = 2;
			assertFalse(LockScreenForm.tryLaunchResume(target, new Intent("fixture"), null));
			assertEquals(1, target.calls);
		});
	}
	@Test public void successfulLaunchKeepsIntentAndAnimationOptionsAndNullDoesNotLaunch() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Target target = new Target(); Intent intent = new Intent("fixture"); Bundle options = new Bundle();
			assertTrue(LockScreenForm.tryLaunchResume(target, intent, options));
			assertSame(intent, target.received); assertSame(options, target.options); assertEquals(1, target.calls);
			assertTrue(LockScreenForm.tryLaunchResume(target, null, null)); assertEquals(1, target.calls);
		});
	}
}
