package com.mishiranu.dashchan;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.preference.Material3DesignFragment;
import com.mishiranu.dashchan.ui.preference.SettingsSearchIndex;
import com.mishiranu.dashchan.util.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Dependency and retained choices, independent of the system animation scale. */
@RunWith(AndroidJUnit4.class)
public class Material3PostingPreferenceTest {
	@Test public void parentSuspendsChildWithoutErasingItAndFdroidNeverEnablesIt() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			String parent = Preferences.KEY_NEW_INTERFACE_MOTION, child = Preferences.KEY_NEW_POSTING_FORM;
			Object oldParent = Preferences.PREFERENCES.getAll().get(parent);
			Object oldChild = Preferences.PREFERENCES.getAll().get(child);
			try {
				try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
					editor.put(parent, true).remove(child);
				}
				assertFalse(Preferences.isNewPostingFormEnabled());
				try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) { editor.put(child, true); }
				assertEquals(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION, Preferences.isNewPostingFormEnabled());
				try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) { editor.put(parent, false); }
				assertFalse(Preferences.isNewPostingFormEnabled());
				assertTrue(Preferences.PREFERENCES.getBoolean(child, false));
				try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) { editor.put(parent, true); }
				assertEquals(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION, Preferences.isNewPostingFormEnabled());
			} finally {
				try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
					if (oldParent instanceof Boolean) editor.put(parent, (Boolean) oldParent); else editor.remove(parent);
					if (oldChild instanceof Boolean) editor.put(child, (Boolean) oldChild); else editor.remove(child);
				}
			}
		});
	}

	@Test public void searchRoutesBothSwitchesToTheDesignScreenOnlyInGithub() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			int matches = 0;
			for (SettingsSearchIndex.Entry entry : SettingsSearchIndex.create(context)) {
				if (context.getString(R.string.material3_enable_design).equals(entry.getTitle())
						|| context.getString(R.string.material3_posting_form).equals(entry.getTitle())) {
					assertTrue(entry.createFragment() instanceof Material3DesignFragment);
					matches++;
				}
			}
			assertEquals(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION ? 2 : 0, matches);
		});
	}
}
