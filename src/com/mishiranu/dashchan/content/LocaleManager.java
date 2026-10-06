package com.mishiranu.dashchan.content;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import androidx.core.os.ConfigurationCompat;
import androidx.core.os.LocaleListCompat;
import chan.content.ChanManager;
import com.mishiranu.dashchan.BuildConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LocaleManager {
	public static final List<CharSequence> ENTRIES_LOCALE;
	public static final List<String> VALUES_LOCALE;
	public static final String DEFAULT_LOCALE = "";
	private static final Map<String, Locale> VALUES_LOCALE_OBJECTS;

	private static Locale createLocale(String language, String country) {
		Locale.Builder builder = new Locale.Builder().setLanguage(language);
		if (country != null) builder.setRegion(country);
		return builder.build();
	}

	static {
		int total = BuildConfig.LOCALES.length + 2;
		String[] codes = new String[total];
		String[] names = new String[total];
		Locale[] locales = new Locale[total];
		codes[0] = DEFAULT_LOCALE;
		codes[1] = "en";
		for (int i = 2; i < total; i++) {
			String locale = BuildConfig.LOCALES[i - 2];
			int index = locale.indexOf("-r");
			if (index >= 0) {
				codes[i] = locale.substring(0, index) + "_" + locale.substring(index + 2);
			} else {
				codes[i] = locale;
			}
		}
		names[0] = "System";
		for (int i = 1; i < names.length; i++) {
			String[] splitted = codes[i].split("_");
			String language = splitted[0];
			String country = splitted.length > 1 ? splitted[1] : null;
			Locale locale = createLocale(language, country);
			String displayName = locale.getDisplayName(locale);
			names[i] = displayName.substring(0, 1).toUpperCase(locale) + displayName.substring(1);
			locales[i] = locale;
		}
		ENTRIES_LOCALE = Arrays.asList(names);
		VALUES_LOCALE = Arrays.asList(codes);
		HashMap<String, Locale> valueLocaleObjects = new HashMap<>();
		for (int i = 0; i < codes.length; i++) {
			valueLocaleObjects.put(codes[i], locales[i]);
		}
		VALUES_LOCALE_OBJECTS = Collections.unmodifiableMap(valueLocaleObjects);
	}

	private static final LocaleManager INSTANCE = new LocaleManager();

	public static LocaleManager getInstance() {
		return INSTANCE;
	}

	private LocaleManager() {}

	private Configuration lastConfiguration;
	private Context applicationContext;

	/** Builds the startup/resource configuration without initializing ChanManager or changing globals. */
	public Configuration createEffectiveConfiguration(Configuration base) {
		return createEffectiveConfiguration(base, Preferences.PREFERENCES != null
				? Preferences.getLocale() : DEFAULT_LOCALE);
	}

	static Configuration createEffectiveConfiguration(Configuration base, String localeCode) {
		Configuration effective = new Configuration(base);
		Locale locale = VALUES_LOCALE_OBJECTS.get(localeCode);
		if (locale != null) {
			effective.setLocales(!Locale.US.equals(locale)
					? new LocaleList(locale, Locale.US) : new LocaleList(Locale.US));
		}
		return effective;
	}

	private void updateConfiguration(Configuration configuration) {
		if (lastConfiguration == null || !configuration.equals(lastConfiguration)) {
			lastConfiguration = new Configuration(configuration);
			applicationContext = null;
			ChanManager.getInstance().updateConfiguration(configuration);
		}
	}

	public void onConfigurationChanged(Configuration configuration) {
		applicationContext = null;
		updateConfiguration(createEffectiveConfiguration(configuration));
	}

	public Context apply(Context context) {
		Resources resources = context.getResources();
		Configuration configuration = createEffectiveConfiguration(resources.getConfiguration());
		if (!configuration.equals(resources.getConfiguration())) {
			context = context.createConfigurationContext(configuration);
		}
		LocaleList locales = configuration.getLocales();
		Locale.setDefault(locales.isEmpty() ? Locale.US : locales.get(0));
		updateConfiguration(configuration);
		return context;
	}

	public Context applyApplication(Context context) {
		if (applicationContext == null) {
			applicationContext = apply(context.getApplicationContext());
		}
		return applicationContext;
	}

	public List<Locale> getLocales(Configuration configuration) {
		ArrayList<Locale> locales = new ArrayList<>();
		LocaleListCompat localeList = ConfigurationCompat.getLocales(configuration);
		for (int i = 0; i < localeList.size(); i++) {
			locales.add(localeList.get(i));
		}
		return locales;
	}
}
