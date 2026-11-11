package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Disposable preferences only; upgrade enables both features once, never resets later choices. */
@RunWith(AndroidJUnit4.class)
public class StableMediaDefaultsTest {
	@Test public void oldOptOutsAreMigratedOnceAndLaterChoicesSurviveReopening() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			String name = "stable-media-fixture-" + UUID.randomUUID();
			try {
				SharedPreferences preferences = new SharedPreferences(context, name);
				preferences.edit().put(Preferences.KEY_NEW_PHOTO_EDITOR, false)
						.put(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, false).put("unrelated", "keep").close();
				Preferences.migrateStableMediaDefaults(preferences);
				assertTrue(preferences.getBoolean(Preferences.KEY_NEW_PHOTO_EDITOR, false));
				assertTrue(preferences.getBoolean(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, false));
				assertEquals("keep", preferences.getString("unrelated", null));
				preferences.edit().put(Preferences.KEY_NEW_PHOTO_EDITOR, false)
						.put(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, false).close();
				SharedPreferences reopened = new SharedPreferences(context, name);
				Preferences.migrateStableMediaDefaults(reopened);
				assertFalse(reopened.getBoolean(Preferences.KEY_NEW_PHOTO_EDITOR, true));
				assertFalse(reopened.getBoolean(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, true));
			} finally { context.deleteSharedPreferences(name); }
		});
	}
	@Test public void freshInstallUsesBothDefaultsOn() {
		assertTrue(Preferences.DEFAULT_NEW_PHOTO_EDITOR);
		assertTrue(Preferences.DEFAULT_UPDATE_VIDEO_THUMBNAILS);
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			String name = "stable-media-fresh-" + UUID.randomUUID();
			try {
				SharedPreferences preferences = new SharedPreferences(context, name);
				Preferences.migrateStableMediaDefaults(preferences);
				assertTrue(preferences.getBoolean(Preferences.KEY_NEW_PHOTO_EDITOR, false));
				assertTrue(preferences.getBoolean(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, false));
			} finally { context.deleteSharedPreferences(name); }
		});
	}
}
