package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.os.Build;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.util.SystemNavigationMode;
import com.mishiranu.dashchan.util.SystemNavigationModeFixture;
import com.mishiranu.dashchan.widget.StandardContentTestActivity;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BackGestureSettingsTest {
	@Test public void postingOptionFollowsSystemModeAndKeepsItsValue() {
		assumeTrue(Build.VERSION.SDK_INT >= 34 && BuildConfig.ENABLE_PREDICTIVE_BACK);
		SystemNavigationMode.Mode previousMode = SystemNavigationMode.get();
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> {
				PredictiveBackSettingsFragment screen = new PredictiveBackSettingsFragment();
				activity.getSupportFragmentManager().beginTransaction().replace(android.R.id.widget_frame, screen).commitNow();
				assertNull(screen.findPreference("predictive_back"));
				Preference<?> posting = screen.findPreference(Preferences.KEY_POSTING_BACK_SINGLE_GESTURE);
				Object saved = posting.getValue();

				SystemNavigationModeFixture.setMode(SystemNavigationMode.Mode.BUTTONS);
				assertFalse(posting.isEnabled());
				SystemNavigationModeFixture.setMode(SystemNavigationMode.Mode.GESTURES);
				assertTrue(posting.isEnabled());
				assertEquals(saved, posting.getValue());
			});
		} finally {
			SystemNavigationModeFixture.setMode(previousMode);
		}
	}
}
