package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import android.content.res.Configuration;
import android.os.LocaleList;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import chan.content.Chan;
import chan.content.ChanManager;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ExtensionLocaleSmokeTest {
	@Test public void initialLocalePreparationUsesPreferenceWithoutUpdatingManagerOrDefaultLocale() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			boolean existed = Preferences.PREFERENCES.getAll().containsKey(Preferences.KEY_LOCALE);
			String saved = Preferences.getLocale();
			Locale defaultLocale = Locale.getDefault();
			Chan chan = Chan.get("dvach");
			long generation = chan.configuration.getResourcesGenerationInternal();
			Configuration base = new Configuration(chan.configuration.getResources().getConfiguration());
			base.setLocales(new LocaleList(Locale.US));
			try {
				try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
					editor.put(Preferences.KEY_LOCALE, "ru");
				}
				Configuration effective = LocaleManager.getInstance().createEffectiveConfiguration(base);
				assertEquals("ru", effective.getLocales().get(0).getLanguage());
				assertEquals("en", base.getLocales().get(0).getLanguage());
				assertEquals(defaultLocale, Locale.getDefault());
				assertEquals(generation, chan.configuration.getResourcesGenerationInternal());
			} finally {
				try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
					if (existed) editor.put(Preferences.KEY_LOCALE, saved);
					else editor.remove(Preferences.KEY_LOCALE);
				}
			}
		});
	}

	@Test public void systemLocaleModePreservesBaseLocaleList() {
		Configuration base = new Configuration();
		base.setLocales(new LocaleList(Locale.JAPAN, Locale.US));
		Configuration effective = LocaleManager.createEffectiveConfiguration(base, LocaleManager.DEFAULT_LOCALE);
		assertNotSame(base, effective);
		assertEquals(base.getLocales(), effective.getLocales());
	}

	@Test public void resourceGenerationChangesMemoryKeyEvenForSavedLegacyUri() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Chan chan = Chan.get("dvach");
			Configuration original = new Configuration(chan.configuration.getResources().getConfiguration());
			String legacyKey = "chan:///res/drawable/ic_extension";
			String before = ImageMemoryKey.create(chan.name, 100, legacyKey, true,
					chan.configuration.getResourcesGenerationInternal());
			Configuration changed = new Configuration(original);
			changed.setLocales(new LocaleList("ru".equals(original.getLocales().get(0).getLanguage())
					? Locale.US : Locale.forLanguageTag("ru")));
			try {
				ChanManager.getInstance().updateConfiguration(changed);
				String after = ImageMemoryKey.create(chan.name, 100, legacyKey, true,
						chan.configuration.getResourcesGenerationInternal());
				assertNotEquals(before, after);
				assertEquals(ImageMemoryKey.create(chan.name, 100, legacyKey, false, 1L),
						ImageMemoryKey.create(chan.name, 100, legacyKey, false, 2L));
			} finally {
				ChanManager.getInstance().updateConfiguration(original);
			}
		});
	}
}
