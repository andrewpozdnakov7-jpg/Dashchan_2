package com.mishiranu.dashchan.content.async;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import chan.content.Chan;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.util.IOUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

public class ReadChangelogTask extends ExecutorTask<Void, ReadChangelogTask.Result> {
	private static final long NETWORK_FALLBACK_TIMEOUT_MS = 20 * 1000L;

	public interface Callback {
		void onReadChangelogComplete(List<Entry> entries, ErrorItem errorItem, boolean localFallback);
	}

	public static class Result {
		public final ErrorItem errorItem;
		public final List<Entry> entries;
		public final boolean localFallback;

		public Result(ErrorItem errorItem, List<Entry> entries, boolean localFallback) {
			this.errorItem = errorItem;
			this.entries = entries;
			this.localFallback = localFallback;
		}
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

	private interface ChangelogReader {
		String read(String... pathSegments) throws HttpException;
	}

	private static class NetworkResult {
		public boolean done;
		public boolean interrupted;
		public List<Entry> entries;
		public HttpException httpException;
		public JSONException jsonException;
	}

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

	private static String buildAssetPath(String... pathSegments) {
		StringBuilder builder = new StringBuilder();
		for (String pathSegment : pathSegments) {
			for (String segment : pathSegment.split("/")) {
				if (!segment.isEmpty()) {
					if (builder.length() > 0) {
						builder.append('/');
					}
					builder.append(segment);
				}
			}
		}
		return builder.toString();
	}

	private static String readBundledStringOrNull(String... pathSegments) {
		String assetPath = buildAssetPath(pathSegments);
		try (InputStream input = MainApplication.getInstance().getAssets().open(assetPath);
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			IOUtils.copyStream(input, output);
			return output.toString("UTF-8");
		} catch (IOException e) {
			return null;
		}
	}

	private List<Entry> readEntries(ChangelogReader reader) throws HttpException, JSONException, InterruptedException {
		String versionsFile = reader.read("versions.json");
		if (versionsFile == null) {
			return null;
		}
		if (isCancelled()) {
			return null;
		}
		JSONArray versionsArray = new JSONObject(versionsFile).getJSONArray("versions");
		ArrayList<String> downloadLocales = collectDownloadLocales(locales);
		if (downloadLocales.isEmpty()) {
			throw new JSONException("No locales");
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
				String changelog = reader.read(localeDir, "changelogs", code + ".txt");
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
			return null;
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
		return entries;
	}

	private List<Entry> readNetworkEntriesWithTimeout(Uri githubUri, String metadataPath) throws InterruptedException,
			HttpException, JSONException {
		HttpHolder holder = new HttpHolder(Chan.getFallback());
		NetworkResult result = new NetworkResult();
		Thread thread = new Thread(() -> {
			try {
				result.entries = readEntries(pathSegments ->
						downloadStringOrNull(holder, githubUri, metadataPath, pathSegments));
			} catch (InterruptedException e) {
				result.interrupted = true;
			} catch (HttpException e) {
				result.httpException = e;
			} catch (JSONException e) {
				result.jsonException = e;
			} finally {
				synchronized (result) {
					result.done = true;
					result.notifyAll();
				}
			}
		}, "ReadChangelogNetwork");
		thread.setDaemon(true);
		thread.start();
		long end = SystemClock.elapsedRealtime() + NETWORK_FALLBACK_TIMEOUT_MS;
		synchronized (result) {
			while (!result.done) {
				long timeout = end - SystemClock.elapsedRealtime();
				if (timeout <= 0) {
					break;
				}
				result.wait(timeout);
			}
		}
		if (!result.done) {
			holder.interrupt();
			return null;
		}
		if (result.interrupted) {
			throw new InterruptedException();
		}
		if (result.httpException != null) {
			throw result.httpException;
		}
		if (result.jsonException != null) {
			throw result.jsonException;
		}
		return result.entries;
	}

	@Override
	protected Result run() throws InterruptedException {
		Uri githubUri = Chan.getFallback().locator.setSchemeIfEmpty(Uri.parse(BuildConfig.GITHUB_URI_METADATA), null);
		String metadataPath = BuildConfig.GITHUB_PATH_METADATA;
		try {
			List<Entry> entries = null;
			boolean localFallback = false;
			try {
				entries = readNetworkEntriesWithTimeout(githubUri, metadataPath);
			} catch (HttpException | JSONException e) {
				// Use bundled metadata when GitHub is unavailable or returns invalid data.
			}
			if (entries == null) {
				entries = readEntries(ReadChangelogTask::readBundledStringOrNull);
				localFallback = entries != null;
			}

			if (entries != null) {
				return new Result(null, entries, localFallback);
			} else {
				throw HttpException.createNotFoundException();
			}
		} catch (HttpException e) {
			return new Result(e.getErrorItemAndHandle(), null, false);
		} catch (JSONException e) {
			e.printStackTrace();
			return new Result(new ErrorItem(ErrorItem.Type.INVALID_RESPONSE), null, false);
		}
	}

	@Override
	protected void onComplete(Result result) {
		callback.onReadChangelogComplete(result.entries, result.errorItem, result.localFallback);
	}
}
