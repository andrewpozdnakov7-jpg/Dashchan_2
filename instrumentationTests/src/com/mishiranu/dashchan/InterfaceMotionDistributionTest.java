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
	@Test public void predictiveSwitchIsIndependentOfDesignAndRespectsTheDistribution() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			String[] keys = {Preferences.KEY_NEW_INTERFACE_MOTION, Preferences.KEY_PREDICTIVE_BACK};
			java.util.Map<String, ?> before = Preferences.PREFERENCES.getAll();
			try {
				for (boolean design : new boolean[]{false, true}) {
					for (boolean predictive : new boolean[]{false, true}) {
						Preferences.PREFERENCES.edit().put(keys[0], design).put(keys[1], predictive).close();
						assertEquals(BuildConfig.ENABLE_PREDICTIVE_BACK && predictive, Preferences.isPredictiveBackEnabled());
						assertEquals(BuildConfig.ENABLE_PREDICTIVE_BACK && Build.VERSION.SDK_INT >= 33 && predictive,
								InterfaceMotion.isPredictiveBackEnabled());
						assertEquals(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION && design, InterfaceMotion.isEnabled());
					}
				}
			} finally {
				try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
					for (String key : keys) {
						Object value = before.get(key);
						if (value instanceof Boolean) editor.put(key, (Boolean) value);
						else editor.remove(key);
					}
				}
			}
		});
	}

	@Test public void sharedDesignHonorsExplicitOptOutInBothDistributions() {
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

	@Test public void searchOffersSharedDesignAndOnlyAvailablePredictiveBack() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			int matches = 0;
			int predictiveMatches = 0;
			for (SettingsSearchIndex.Entry entry : SettingsSearchIndex.create(context)) {
				if (context.getString(R.string.material3_enable_design).equals(entry.getTitle())) matches++;
				if (context.getString(R.string.enable_predictive_back).equals(entry.getTitle())) predictiveMatches++;
			}
			assertEquals(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION ? 1 : 0, matches);
			assertEquals(BuildConfig.ENABLE_PREDICTIVE_BACK ? 1 : 0, predictiveMatches);
		});
	}
}
