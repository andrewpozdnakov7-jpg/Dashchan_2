package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Disposable preferences only; never changes the test device's launcher components. */
@RunWith(AndroidJUnit4.class)
public class LauncherIconDefaultsTest {
	@Test public void freshStoreUsesMaterialYouAndRetainsLaterManualChoice() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			String name = "launcher-default-fixture-" + UUID.randomUUID();
			try {
				SharedPreferences preferences = new SharedPreferences(context, name);
				LauncherIconManager.initializeInstallationLogo(preferences, false);
				assertEquals(LauncherIconManager.LOGO_MATERIAL_YOU,
						preferences.getString(Preferences.KEY_APPLICATION_LOGO, null));
				SharedPreferences reopened = new SharedPreferences(context, name);
				assertEquals(LauncherIconManager.LOGO_MATERIAL_YOU,
						reopened.getString(Preferences.KEY_APPLICATION_LOGO, null));
				reopened.edit().put(Preferences.KEY_APPLICATION_LOGO, LauncherIconManager.LOGO_OLD_SCHOOL).close();
				LauncherIconManager.initializeInstallationLogo(reopened, true);
				// A repeated fresh-init call must not replace an explicit selection either.
				LauncherIconManager.initializeInstallationLogo(reopened, false);
				assertEquals(LauncherIconManager.LOGO_OLD_SCHOOL,
						reopened.getString(Preferences.KEY_APPLICATION_LOGO, null));
			} finally { context.deleteSharedPreferences(name); }
		});
	}

	@Test public void upgradeOrRestoredStorePreservesBothAbsentAndExplicitLogoKeys() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			String name = "launcher-upgrade-fixture-" + UUID.randomUUID();
			try {
				SharedPreferences preferences = new SharedPreferences(context, name);
				preferences.edit().put("fixture_existing_setting", true).close();
				LauncherIconManager.initializeInstallationLogo(preferences, true);
				assertFalse(preferences.contains(Preferences.KEY_APPLICATION_LOGO));
				assertEquals(LauncherIconManager.LOGO_DEFAULT,
						preferences.getString(Preferences.KEY_APPLICATION_LOGO, Preferences.DEFAULT_APPLICATION_LOGO));
				for (LauncherIconManager.LogoOption option : LauncherIconManager.getLogoOptions()) {
					preferences.edit().put(Preferences.KEY_APPLICATION_LOGO, option.value).close();
					LauncherIconManager.initializeInstallationLogo(preferences, true);
					assertEquals(option.value, preferences.getString(Preferences.KEY_APPLICATION_LOGO, null));
				}
				assertTrue(preferences.getBoolean("fixture_existing_setting", false));
			} finally { context.deleteSharedPreferences(name); }
		});
	}
}
