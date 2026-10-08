package com.mishiranu.dashchan;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.preference.SettingsSearchIndex;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Flavor and restored-preference policy. Does not navigate or change the system animation scale. */
@RunWith(AndroidJUnit4.class)
public class InterfaceMotionDistributionTest {
	@Test public void restoredExperimentalPreferenceCannotEnableTheFeatureInFdroid() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			String key = Preferences.KEY_NEW_INTERFACE_MOTION;
			Object previous = Preferences.PREFERENCES.getAll().get(key);
			try {
				try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
					editor.put(key, true);
				}
				assertEquals(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION,
						Preferences.isNewInterfaceMotionEnabled());
				assertEquals(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION, InterfaceMotion.isEnabled());
				if (!BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION) {
					assertFalse(InterfaceMotion.isDrawerEnabled());
					assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
							Preferences.isPredictiveBackEnabled(), InterfaceMotion.isPredictiveBackEnabled());
				}
				try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
					editor.put(key, false);
				}
				assertFalse(Preferences.isNewInterfaceMotionEnabled());
				assertFalse(InterfaceMotion.isEnabled());
			} finally {
				try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
					if (previous instanceof Boolean) editor.put(key, (Boolean) previous);
					else editor.remove(key);
				}
			}
		});
	}

	@Test public void searchOnlyOffersExperimentalInterfaceInGithub() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			int matches = 0;
			for (SettingsSearchIndex.Entry entry : SettingsSearchIndex.create(context)) {
				if (context.getString(R.string.new_interface_motion).equals(entry.getTitle())) matches++;
			}
			assertEquals(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION ? 1 : 0, matches);
		});
	}
}
