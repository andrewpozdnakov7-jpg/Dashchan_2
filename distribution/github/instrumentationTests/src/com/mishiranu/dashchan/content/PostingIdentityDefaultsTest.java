package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Only randomly named disposable settings stores; never reads/writes real identity or drafts. */
@RunWith(AndroidJUnit4.class)
public class PostingIdentityDefaultsTest {
	@Test public void freshAndExistingInstallsHideBothFieldsOnlyOnceAndKeepLaterChoices() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			for (int initial : new int[] {-1, 0, 1}) {
				String name = "posting-identity-fixture-" + UUID.randomUUID();
				try {
					SharedPreferences prefs = new SharedPreferences(context, name);
					try (SharedPreferences.Editor editor = prefs.edit()) {
						editor.put("fixture_identity_value", "keep this value");
						editor.put(Preferences.KEY_POSTING_HIDE_SUBJECT, false);
						editor.put(Preferences.KEY_POSTING_HIDE_PASSWORD, true);
						if (initial >= 0) {
							editor.put(Preferences.KEY_POSTING_HIDE_NAME, initial == 1);
							editor.put(Preferences.KEY_POSTING_HIDE_EMAIL, initial == 1);
						}
					}
					Preferences.migratePostingIdentityVisibility(prefs);
					assertTrue(prefs.getBoolean(Preferences.KEY_POSTING_HIDE_NAME, false));
					assertTrue(prefs.getBoolean(Preferences.KEY_POSTING_HIDE_EMAIL, false));
					assertEquals("keep this value", prefs.getString("fixture_identity_value", null));
					assertFalse(prefs.getBoolean(Preferences.KEY_POSTING_HIDE_SUBJECT, true));
					assertTrue(prefs.getBoolean(Preferences.KEY_POSTING_HIDE_PASSWORD, false));
					prefs.edit().put(Preferences.KEY_POSTING_HIDE_NAME, false).close();
					Preferences.migratePostingIdentityVisibility(prefs);
					assertFalse(prefs.getBoolean(Preferences.KEY_POSTING_HIDE_NAME, true));
					assertTrue(prefs.getBoolean(Preferences.KEY_POSTING_HIDE_EMAIL, false));
					prefs.edit().put(Preferences.KEY_POSTING_HIDE_EMAIL, false).close();
					SharedPreferences reopened = new SharedPreferences(context, name);
					Preferences.migratePostingIdentityVisibility(reopened);
					assertFalse(reopened.getBoolean(Preferences.KEY_POSTING_HIDE_NAME, true));
					assertFalse(reopened.getBoolean(Preferences.KEY_POSTING_HIDE_EMAIL, true));
				} finally { context.deleteSharedPreferences(name); }
			}
		});
	}
}
