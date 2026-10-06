package com.mishiranu.dashchan;

import static org.junit.Assert.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.ui.preference.AboutFragment;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Run on a disposable emulator/test install: MainActivity initializes real app storage.
 * This is Activity recreation, NOT process-death or force-stop coverage. */
@RunWith(AndroidJUnit4.class)
public class NavigationLifecycleSmokeTest {
	@Test public void settingsScreenSurvivesActivityRecreation() {
		try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
			scenario.onActivity(activity -> {
				assertNotNull(activity.findViewById(R.id.drawer_layout));
				activity.pushFragment(new AboutFragment());
			});
			awaitAboutScreen(scenario);
			scenario.recreate();
			scenario.onActivity(activity -> {
				assertNotNull(activity.findViewById(R.id.drawer_layout));
				assertTrue(activity.getSupportFragmentManager().findFragmentById(R.id.content_fragment)
						instanceof AboutFragment);
			});
		}
	}

	private static void awaitAboutScreen(ActivityScenario<MainActivity> scenario) {
		long deadline = SystemClock.elapsedRealtime() + 5000;
		AtomicBoolean shown = new AtomicBoolean();
		do {
			scenario.onActivity(activity -> shown.set(activity.getSupportFragmentManager()
					.findFragmentById(R.id.content_fragment) instanceof AboutFragment));
			if (shown.get()) return;
			// Observe the application's real asynchronous queue; do not force pending transactions.
			SystemClock.sleep(20);
		} while (SystemClock.elapsedRealtime() < deadline);
		fail("Settings navigation did not finish");
	}
}
