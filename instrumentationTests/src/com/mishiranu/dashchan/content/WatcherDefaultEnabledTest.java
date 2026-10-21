package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Uses a uniquely named disposable preferences file, never the user's settings. */
@RunWith(AndroidJUnit4.class)
public class WatcherDefaultEnabledTest {
	@Test public void upgradeOverridesOldFalseOnceAndPersistsLaterOptOut() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			String name = "watcher-default-fixture-" + UUID.randomUUID();
			try {
				SharedPreferences preferences = new SharedPreferences(context, name);
				preferences.edit().put(Preferences.KEY_WATCHER_WATCH_INITIALLY, false)
						.put("fixture_unrelated", "keep").close();
				Preferences.migrateWatcherDefaultEnabled(preferences);
				assertTrue(preferences.getBoolean(Preferences.KEY_WATCHER_WATCH_INITIALLY, false));
				assertEquals("keep", preferences.getString("fixture_unrelated", null));
				preferences.edit().put(Preferences.KEY_WATCHER_WATCH_INITIALLY, false).close();
				SharedPreferences reopened = new SharedPreferences(context, name);
				Preferences.migrateWatcherDefaultEnabled(reopened);
				assertFalse(reopened.getBoolean(Preferences.KEY_WATCHER_WATCH_INITIALLY, true));
			} finally { context.deleteSharedPreferences(name); }
		});
	}

	@Test public void freshInstallUsesDefaultOn() {
		assertTrue(Preferences.DEFAULT_WATCHER_WATCH_INITIALLY);
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			String name = "watcher-fresh-fixture-" + UUID.randomUUID();
			try {
				SharedPreferences preferences = new SharedPreferences(context, name);
				Preferences.migrateWatcherDefaultEnabled(preferences);
				assertTrue(new SharedPreferences(context, name)
						.getBoolean(Preferences.KEY_WATCHER_WATCH_INITIALLY, false));
			} finally { context.deleteSharedPreferences(name); }
		});
	}
}
