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
import com.mishiranu.dashchan.content.update.UpdateConfiguration;
import com.mishiranu.dashchan.util.IOUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ReadChangelogTask extends ExecutorTask<Void, ReadChangelogTask.Result> {
	private static final long NETWORK_FALLBACK_TIMEOUT_MS = 20 * 1000L;
	private static final int MAX_CACHE_SIZE = 2 * 1024 * 1024;
	private static final String CACHE_DIRECTORY = "changelog";

	public enum Mode {LOCAL, REFRESH}

	public interface Callback {
		void onReadChangelogComplete(List<Entry> entries, ErrorItem errorItem);
	}

	public static class Result {
		public final ErrorItem errorItem;
		public final List<Entry> entries;

		public Result(ErrorItem errorItem, List<Entry> entries) {
			this.errorItem = errorItem;
			this.entries = entries;
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
	private final Mode mode;

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

	public ReadChangelogTask(Callback callback, List<Locale> locales, Mode mode) {
		this.callback = callback;
		this.locales = locales;
		this.mode = mode;
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

	private File getCacheFile() {
		StringBuilder key = new StringBuilder(UpdateConfiguration.isBetaChannel() ? "beta" : "stable");
		for (String locale : collectDownloadLocales(locales)) {
			key.append('-').append(locale);
		}
		String fileName = Integer.toHexString(key.toString().hashCode()) + ".json";
		return new File(new File(MainApplication.getInstance().getFilesDir(), CACHE_DIRECTORY), fileName);
	}

	private List<Entry> readCachedEntries() {
		File file = getCacheFile();
		if (!file.isFile() || file.length() <= 0 || file.length() > MAX_CACHE_SIZE) {
			return null;
		}
		try (FileInputStream input = new FileInputStream(file);
				ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
			IOUtils.copyStream(input, output);
			JSONArray entriesArray = new JSONObject(output.toString("UTF-8")).getJSONArray("entries");
			ArrayList<Entry> entries = new ArrayList<>(entriesArray.length());
			for (int i = 0; i < entriesArray.length(); i++) {
				JSONObject entryObject = entriesArray.getJSONObject(i);
				JSONArray versionsArray = entryObject.getJSONArray("versions");
				ArrayList<Entry.Version> versions = new ArrayList<>(versionsArray.length());
				for (int j = 0; j < versionsArray.length(); j++) {
					JSONObject versionObject = versionsArray.getJSONObject(j);
					versions.add(new Entry.Version(versionObject.getInt("code"),
							versionObject.getString("name"), versionObject.getString("date")));
				}
				JSONArray textsArray = entryObject.getJSONArray("texts");
				ArrayList<String> texts = new ArrayList<>(textsArray.length());
				for (int j = 0; j < textsArray.length(); j++) {
					texts.add(textsArray.getString(j));
				}
				if (!versions.isEmpty() && !texts.isEmpty()) {
					entries.add(new Entry(versions, texts));
				}
			}
			return entries;
		} catch (IOException | JSONException e) {
			return null;
		}
	}

	private void writeCachedEntries(List<Entry> entries) {
		File file = getCacheFile();
		File directory = file.getParentFile();
		if ((!directory.isDirectory() && !directory.mkdirs()) || entries == null) {
			return;
		}
		File temporary = new File(directory, file.getName() + ".tmp");
		try {
			JSONArray entriesArray = new JSONArray();
			for (Entry entry : entries) {
				JSONObject entryObject = new JSONObject();
				JSONArray versionsArray = new JSONArray();
				for (Entry.Version version : entry.versions) {
					versionsArray.put(new JSONObject().put("code", version.code)
							.put("name", version.name).put("date", version.date));
				}
				JSONArray textsArray = new JSONArray();
				for (String text : entry.texts) {
					textsArray.put(text);
				}
				entryObject.put("versions", versionsArray).put("texts", textsArray);
				entriesArray.put(entryObject);
			}
			String json = new JSONObject().put("entries", entriesArray).toString();
			byte[] data = json.getBytes(StandardCharsets.UTF_8);
			if (data.length > MAX_CACHE_SIZE) {
				return;
			}
			try (FileOutputStream output = new FileOutputStream(temporary);
					OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
				writer.write(json);
			}
			if (!temporary.renameTo(file)) {
				try (FileInputStream input = new FileInputStream(temporary);
						FileOutputStream output = new FileOutputStream(file)) {
					IOUtils.copyStream(input, output);
				}
			}
		} catch (IOException | JSONException e) {
			// A failed cache write must not prevent the bundled changelog from being shown.
		} finally {
			if (temporary.isFile()) {
				//noinspection ResultOfMethodCallIgnored
				temporary.delete();
			}
		}
	}

	private static int getNewestCode(List<Entry> entries) {
		int newestCode = 0;
		if (entries != null) {
			for (Entry entry : entries) {
				for (Entry.Version version : entry.versions) {
					newestCode = Math.max(newestCode, version.code);
				}
			}
		}
		return newestCode;
	}

	private static List<Entry> mergeDistinctEntries(List<Entry> first, List<Entry> second) {
		TreeMap<Integer, Entry> entriesMap = new TreeMap<>(Collections.reverseOrder());
		for (List<Entry> entries : Arrays.asList(first, second)) {
			if (entries != null) {
				for (Entry entry : entries) {
					if (!entry.versions.isEmpty()) {
						entriesMap.putIfAbsent(entry.versions.get(0).code, entry);
					}
				}
			}
		}
		return new ArrayList<>(entriesMap.values());
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

	private List<Entry> readNewEntries(ChangelogReader reader, int newestKnownCode)
			throws HttpException, JSONException, InterruptedException {
		String versionsFile = reader.read("versions.json");
		if (versionsFile == null || isCancelled()) {
			return null;
		}
		JSONArray versionsArray = new JSONObject(versionsFile).getJSONArray("versions");
		ArrayList<String> downloadLocales = collectDownloadLocales(locales);
		if (downloadLocales.isEmpty()) {
			throw new JSONException("No locales");
		}

		TreeSet<Long> newCodes = new TreeSet<>(Collections.reverseOrder());
		for (int i = 0; i < versionsArray.length(); i++) {
			JSONObject jsonObject = versionsArray.getJSONObject(i);
			long code = jsonObject.getLong("code");
			if (code > newestKnownCode && jsonObject.optBoolean("changelog")) {
				newCodes.add(code);
			}
		}
		if (newCodes.isEmpty()) {
			return Collections.emptyList();
		}

		HashMap<Long, String> changelogs = new HashMap<>();
		for (long code : newCodes) {
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

		TreeMap<Long, Entry> entriesMap = new TreeMap<>(Collections.reverseOrder());
		for (int i = 0; i < versionsArray.length(); i++) {
			JSONObject jsonObject = versionsArray.getJSONObject(i);
			long code = jsonObject.getLong("code");
			String changelog = changelogs.get(code);
			if (changelog == null) {
				continue;
			}
			Entry entry = entriesMap.get(code);
			if (entry == null) {
				entry = new Entry(new ArrayList<>(), new ArrayList<>());
				entry.texts.add(changelog);
				entriesMap.put(code, entry);
			}
			entry.versions.add(new Entry.Version(jsonObject.getInt("code"), jsonObject.getString("name"),
					jsonObject.getString("date")));
		}
		return new ArrayList<>(entriesMap.values());
	}

	private List<Entry> readNetworkEntriesWithTimeout(Uri githubUri, String metadataPath, int newestKnownCode)
			throws InterruptedException, HttpException, JSONException {
		HttpHolder holder = new HttpHolder(Chan.getFallback());
		NetworkResult result = new NetworkResult();
		Thread thread = new Thread(() -> {
			try {
				result.entries = readNewEntries(pathSegments ->
						downloadStringOrNull(holder, githubUri, metadataPath, pathSegments), newestKnownCode);
				if (UpdateConfiguration.isBetaChannel()) {
					try {
						Uri betaGithubUri = Chan.getFallback().locator.setSchemeIfEmpty(
								Uri.parse(UpdateConfiguration.getBetaMetadataUri()), null);
						List<Entry> betaEntries = readNewEntries(pathSegments -> downloadStringOrNull(holder,
								betaGithubUri, UpdateConfiguration.getBetaMetadataPath(), pathSegments), newestKnownCode);
						result.entries = mergeEntries(result.entries, betaEntries);
					} catch (HttpException | JSONException e) {
						// Stable changelog remains available when beta metadata is temporarily unavailable.
					}
				}
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

	private static List<Entry> mergeEntries(List<Entry> stableEntries, List<Entry> betaEntries) {
		if (betaEntries == null || betaEntries.isEmpty()) {
			return stableEntries;
		}
		if (stableEntries == null || stableEntries.isEmpty()) {
			return betaEntries;
		}
		ArrayList<Entry> entries = new ArrayList<>(stableEntries.size() + betaEntries.size());
		entries.addAll(stableEntries);
		entries.addAll(betaEntries);
		entries.sort((left, right) -> Integer.compare(right.versions.get(0).code, left.versions.get(0).code));
		return entries;
	}

	@Override
	protected Result run() throws InterruptedException {
		try {
			List<Entry> bundledEntries = readEntries(ReadChangelogTask::readBundledStringOrNull);
			List<Entry> cachedEntries = readCachedEntries();
			List<Entry> localEntries = mergeDistinctEntries(bundledEntries, cachedEntries);
			if (mode == Mode.LOCAL) {
				return !localEntries.isEmpty() ? new Result(null, localEntries) :
						new Result(new ErrorItem(ErrorItem.Type.EMPTY_RESPONSE), null);
			}

			Uri githubUri = Chan.getFallback().locator.setSchemeIfEmpty(
					Uri.parse(BuildConfig.GITHUB_URI_METADATA), null);
			List<Entry> newEntries;
			try {
				newEntries = readNetworkEntriesWithTimeout(githubUri, BuildConfig.GITHUB_PATH_METADATA,
						getNewestCode(localEntries));
			} catch (HttpException | JSONException e) {
				return new Result(null, null);
			}
			if (newEntries == null || newEntries.isEmpty()) {
				return new Result(null, null);
			}
			List<Entry> updatedCache = mergeDistinctEntries(newEntries, cachedEntries);
			writeCachedEntries(updatedCache);
			return new Result(null, mergeDistinctEntries(newEntries, localEntries));
		} catch (HttpException e) {
			return new Result(e.getErrorItemAndHandle(), null);
		} catch (JSONException e) {
			e.printStackTrace();
			return new Result(new ErrorItem(ErrorItem.Type.INVALID_RESPONSE), null);
		}
	}

	@Override
	protected void onComplete(Result result) {
		callback.onReadChangelogComplete(result.entries, result.errorItem);
	}
}
