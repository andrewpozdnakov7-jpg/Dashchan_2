package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class Material3DesignOptionsTest {
	@Test public void enablingDesignSelectsAllOptionsAndDisablingKeepsIndividualChoices() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			CheckPreference form = new CheckPreference(context, "test_form", false, "Form", null);
			CheckPreference player = new CheckPreference(context, "test_player", true, "Player", null);
			form.setValue(false); player.setValue(false);
			Material3DesignFragment.onDesignToggled(true, form, player);
			assertTrue(form.getValue()); assertTrue(player.getValue());
			assertTrue(form.isEnabled()); assertTrue(player.isEnabled());
			player.setValue(false);
			assertTrue(form.getValue()); assertFalse(player.getValue());
			Material3DesignFragment.onDesignToggled(false, form, player);
			assertFalse(form.isEnabled()); assertFalse(player.isEnabled());
			assertTrue(form.getValue()); assertFalse(player.getValue());
			Material3DesignFragment.onDesignToggled(true, form, player);
			assertTrue(form.getValue()); assertTrue(player.getValue());
		});
	}

	@Test public void playerChoiceIsIndependentButRequiresParentDesign() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			String[] keys = {Preferences.KEY_NEW_INTERFACE_MOTION,
					Preferences.KEY_NEW_POSTING_FORM, Preferences.KEY_NEW_VIDEO_PLAYER};
			Map<String, ?> before = Preferences.PREFERENCES.getAll();
			try {
				Preferences.PREFERENCES.edit().put(keys[0], true).remove(keys[1]).remove(keys[2]).close();
				// Users with the design already enabled keep their current player when upgrading.
				assertTrue(Preferences.isNewVideoPlayerEnabled());
				assertTrue(Preferences.isNewPostingFormEnabled());
				Preferences.PREFERENCES.edit().put(keys[1], false).close();
				assertFalse(Preferences.isNewPostingFormEnabled());
				assertTrue(Preferences.isNewVideoPlayerEnabled());
				Preferences.PREFERENCES.edit().put(keys[1], true).close();
				Preferences.PREFERENCES.edit().put(keys[2], false).close();
				assertFalse(Preferences.isNewVideoPlayerEnabled());
				assertTrue(Preferences.isNewPostingFormEnabled());
				assertTrue(Preferences.isNewInterfaceMotionEnabled());
				Preferences.PREFERENCES.edit().put(keys[1], false).put(keys[2], true).close();
				assertTrue(Preferences.isNewVideoPlayerEnabled());
				assertFalse(Preferences.isNewPostingFormEnabled());
				Preferences.PREFERENCES.edit().put(keys[0], false).close();
				assertFalse(Preferences.isNewVideoPlayerEnabled());
				assertFalse(Preferences.isNewPostingFormEnabled());
				assertTrue(Preferences.PREFERENCES.getBoolean(keys[2], false));
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
}
