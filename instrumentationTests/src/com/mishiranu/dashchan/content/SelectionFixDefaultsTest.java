package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Disposable settings: enable for the rollout once, preserving subsequent user choices. NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class SelectionFixDefaultsTest {
	@Test public void upgradeEnablesOnceAndKeepsLaterOptOutAfterReopen() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			String name = "selection-rollout-" + UUID.randomUUID();
			try {
				SharedPreferences preferences = new SharedPreferences(context, name);
				preferences.edit().put(Preferences.KEY_GALAXY_M12_SELECTION_FIX, false).put("unrelated", "keep").close();
				Preferences.migrateSelectionFixDefaultOn(preferences);
				assertTrue(preferences.getBoolean(Preferences.KEY_GALAXY_M12_SELECTION_FIX, false));
				assertEquals("keep", preferences.getString("unrelated", null));
				preferences.edit().put(Preferences.KEY_GALAXY_M12_SELECTION_FIX, false).close();
				SharedPreferences reopened = new SharedPreferences(context, name);
				Preferences.migrateSelectionFixDefaultOn(reopened);
				assertFalse(reopened.getBoolean(Preferences.KEY_GALAXY_M12_SELECTION_FIX, true));
			} finally { context.deleteSharedPreferences(name); }
		});
	}
	@Test public void freshInstallIsEnabledAndMigrationIsIdempotent() {
		assertTrue(Preferences.DEFAULT_GALAXY_M12_SELECTION_FIX);
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			String name = "selection-fresh-" + UUID.randomUUID();
			try {
				SharedPreferences preferences = new SharedPreferences(context, name);
				Preferences.migrateSelectionFixDefaultOn(preferences);
				Preferences.migrateSelectionFixDefaultOn(preferences);
				assertTrue(new SharedPreferences(context, name).getBoolean(Preferences.KEY_GALAXY_M12_SELECTION_FIX, false));
			} finally { context.deleteSharedPreferences(name); }
		});
	}
}
