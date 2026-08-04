package com.mishiranu.dashchan.content.update;

import android.content.Context;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.LocaleManager;
import com.mishiranu.dashchan.util.IOUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class WhatsNewData {
	public static final class Release {
		public final int code;
		public final String name;
		public final String text;

		private Release(int code, String name, String text) {
			this.code = code;
			this.name = name;
			this.text = text;
		}
	}

	private WhatsNewData() {}

	public static Release readCurrent(Context context) {
		try {
			JSONObject versionsObject = new JSONObject(readAsset(context, "versions.json"));
			JSONArray versions = versionsObject.getJSONArray("versions");
			int code = 0;
			String name = null;
			for (int i = 0; i < versions.length(); i++) {
				JSONObject version = versions.getJSONObject(i);
				if (version.optBoolean("changelog") &&
						BuildConfig.VERSION_NAME.equals(version.optString("name"))) {
					code = version.getInt("code");
					name = version.getString("name");
				}
			}
			if (code <= 0 || name == null) {
				return null;
			}
			String text = readLocalizedChangelog(context, code);
			return text != null ? new Release(code, name, text) : null;
		} catch (IOException | JSONException e) {
			return null;
		}
	}

	private static String readLocalizedChangelog(Context context, int code) {
		LinkedHashSet<String> directories = new LinkedHashSet<>();
		List<Locale> locales = LocaleManager.getInstance().getLocales(
				context.getResources().getConfiguration());
		for (Locale locale : locales) {
			String language = locale.getLanguage();
			String country = locale.getCountry();
			if (!language.isEmpty()) {
				if (!country.isEmpty()) {
					directories.add(language + "-" + country);
				}
				directories.add(language);
			}
		}
		directories.add("en-US");
		directories.add("en");
		for (String directory : directories) {
			try {
				return readAsset(context, directory + "/changelogs/" + code + ".txt");
			} catch (IOException ignored) {}
		}
		return null;
	}

	private static String readAsset(Context context, String path) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (InputStream input = context.getAssets().open(path)) {
			IOUtils.copyStream(input, output);
		}
		return output.toString("UTF-8");
	}
}
