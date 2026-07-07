package com.mishiranu.dashchan.content.async;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;
import chan.content.Chan;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.model.ErrorItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ReadChangelogTask extends ExecutorTask<Void, Pair<ErrorItem, List<ReadChangelogTask.Entry>>> {
	public interface Callback {
		void onReadChangelogComplete(List<Entry> entries, ErrorItem errorItem);
	}

	public static class Entry implements Parcelable {
		public static class Version {
			public final int code;
			public final String name;
			public final String date;

			public Version(int code, String name, String date) {
				this.code = code;
				this.name = name;
				this.date = date;
			}
		}

		public final List<Version> versions;
		public final List<String> texts;

		public Entry(List<Version> versions, List<String> texts) {
			this.versions = versions;
			this.texts = texts;
		}

		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(Parcel dest, int flags) {
			dest.writeInt(versions.size());
			for (Version version : versions) {
				dest.writeInt(version.code);
				dest.writeString(version.name);
				dest.writeString(version.date);
			}
			dest.writeInt(texts.size());
			for (String text : texts) {
				dest.writeString(text);
			}
		}

		public static final Creator<Entry> CREATOR = new Creator<Entry>() {
			@Override
			public Entry createFromParcel(Parcel source) {
				int versionsSize = source.readInt();
				ArrayList<Version> versions = new ArrayList<>(versionsSize);
				for (int i = 0; i < versionsSize; i++) {
					int code = source.readInt();
					String name = source.readString();
					String date = source.readString();
					versions.add(new Version(code, name, date));
				}
				int textsSize = source.readInt();
				ArrayList<String> texts = new ArrayList<>(textsSize);
				for (int i = 0; i < textsSize; i++) {
					String text = source.readString();
					texts.add(text);
				}
				return new Entry(versions, texts);
			}

			@Override
			public Entry[] newArray(int size) {
				return new Entry[size];
			}
		};
	}

	private final Callback callback;
	private final List<Locale> locales;

	public ReadChangelogTask(Callback callback, List<Locale> locales) {
		this.callback = callback;
		this.locales = locales;
	}

	private static void appendPathSegments(Uri.Builder builder, String path) {
		for (String segment : path.split("/")) {
			if (!segment.isEmpty()) {
				builder.appendPath(segment);
			}
		}
	}

	private static Uri buildRawGithubUri(Uri githubUri, String metadataPath, String... pathSegments) {
		Uri.Builder builder = new Uri.Builder().scheme(githubUri.getScheme())
				.authority("raw.githubusercontent.com");
		for (String segment : githubUri.getPathSegments()) {
			builder.appendPath(segment);
		}
		builder.appendPath("master");
		appendPathSegments(builder, metadataPath);
		for (String pathSegment : pathSegments) {
			appendPathSegments(builder, pathSegment);
		}
		return builder.build();
	}

	private static String downloadString(HttpHolder holder, Uri githubUri, String metadataPath, String... pathSegments)
			throws HttpException {
		Uri uri = buildRawGithubUri(githubUri, metadataPath, pathSegments);
		try (HttpHolder.Use ignored = holder.use()) {
			HttpResponse response = new HttpRequest(uri, holder).perform();
			response.setEncoding("UTF-8");
			return response.readString();
		}
	}

	private static String downloadStringOrNull(HttpHolder holder, Uri githubUri,
			String metadataPath, String... pathSegments) throws HttpException {
		try {
			return downloadString(holder, githubUri, metadataPath, pathSegments);
		} catch (HttpException e) {
			if (e.isHttpException() && e.getResponseCode() == 404) {
				return null;
			}
			throw e;
		}
	}

	private static boolean addLocale(ArrayList<String> downloadLocales, HashSet<String> addedLocales, String locale) {
		if (locale != null && !locale.isEmpty() && !addedLocales.contains(locale)) {
			addedLocales.add(locale);
			downloadLocales.add(locale);
			return true;
		}
		return false;
	}

	private static ArrayList<String> collectDownloadLocales(List<Locale> locales) {
		ArrayList<String> downloadLocales = new ArrayList<>();
		HashSet<String> addedLocales = new HashSet<>();
		for (Locale locale : locales) {
			String language = locale.getLanguage();
			String country = locale.getCountry();
			if (language != null && !language.isEmpty()) {
				if (country != null && !country.isEmpty()) {
					addLocale(downloadLocales, addedLocales, language + "-" + country);
				}
				addLocale(downloadLocales, addedLocales, language);
			}
		}
		for (String fallbackLocaleDir : Arrays.asList("en-US", "en")) {
			addLocale(downloadLocales, addedLocales, fallbackLocaleDir);
		}
		return downloadLocales;
	}

	@Override
	protected Pair<ErrorItem, List<Entry>> run() throws InterruptedException {
		Uri githubUri = Chan.getFallback().locator.setSchemeIfEmpty(Uri.parse(BuildConfig.GITHUB_URI_METADATA), null);
		String metadataPath = BuildConfig.GITHUB_PATH_METADATA;
		HttpHolder holder = new HttpHolder(Chan.getFallback());
		try {
			String versionsFile = downloadString(holder, githubUri, metadataPath, "versions.json");
			if (isCancelled()) {
				return null;
			}
			JSONArray versionsArray = new JSONObject(versionsFile).getJSONArray("versions");
			ArrayList<String> downloadLocales = collectDownloadLocales(locales);
			if (downloadLocales.isEmpty()) {
				return new Pair<>(new ErrorItem(ErrorItem.Type.UNKNOWN), null);
			}

			HashSet<Long> changelogCodesSet = new HashSet<>();
			ArrayList<Long> changelogCodes = new ArrayList<>();
			for (int i = 0; i < versionsArray.length(); i++) {
				JSONObject jsonObject = versionsArray.getJSONObject(i);
				if (jsonObject.optBoolean("changelog")) {
					long code = jsonObject.getLong("code");
					if (!changelogCodesSet.contains(code)) {
						changelogCodesSet.add(code);
						changelogCodes.add(code);
					}
				}
			}
			HashMap<Long, String> changelogs = new HashMap<>();
			for (long code : changelogCodes) {
				for (String localeDir : downloadLocales) {
					String changelog = downloadStringOrNull(holder, githubUri, metadataPath,
							localeDir, "changelogs", code + ".txt");
					if (isCancelled()) {
						return null;
					}
					if (changelog != null) {
						changelogs.put(code, changelog);
						break;
					}
				}
			}
			if (changelogs.isEmpty()) {
				throw HttpException.createNotFoundException();
			}

			TreeMap<Long, Entry> entriesMap = new TreeMap<>();
			for (int i = 0; i < versionsArray.length(); i++) {
				JSONObject jsonObject = versionsArray.getJSONObject(i);
				int code = jsonObject.getInt("code");
				String name = jsonObject.getString("name");
				String date = jsonObject.getString("date");
				Entry entry = entriesMap.get((long) code);
				if ((entry == null || entry.texts.isEmpty()) && jsonObject.optBoolean("changelog")) {
					String changelog = changelogs.get((long) code);
					if (changelog != null) {
						entry = new Entry(entry != null ? entry.versions : new ArrayList<>(),
								entry != null ? entry.texts : new ArrayList<>());
						entry.texts.add(changelog);
						entriesMap.put((long) code, entry);
					}
				}
				if (entry == null) {
					entry = new Entry(new ArrayList<>(), new ArrayList<>());
					entriesMap.put((long) code, entry);
				}
				entry.versions.add(new Entry.Version(code, name, date));
			}

			ArrayList<Entry> entries = new ArrayList<>(entriesMap.size());
			for (Entry entry : entriesMap.values()) {
				if (!entry.texts.isEmpty()) {
					entries.add(entry);
				}
			}
			Collections.reverse(entries);
			return new Pair<>(null, entries);
		} catch (HttpException e) {
			return new Pair<>(e.getErrorItemAndHandle(), null);
		} catch (JSONException e) {
			e.printStackTrace();
			return new Pair<>(new ErrorItem(ErrorItem.Type.INVALID_RESPONSE), null);
		}
	}

	@Override
	protected void onComplete(Pair<ErrorItem, List<Entry>> result) {
		callback.onReadChangelogComplete(result.second, result.first);
	}
}
