package com.mishiranu.dashchan.content.update;

import android.content.Context;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.ExecutorTask;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.IOUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import org.json.JSONException;

public class UpdateChecker {
	public static final long REMIND_LATER_INTERVAL_MS = 24 * 60 * 60 * 1000L;
	private static final int TIMEOUT_MS = 10_000;
	private static final String USER_AGENT = "Dashchan_2 UpdateChecker";

	public interface Callback {
		void onResult(UpdateResult result);
	}

	public static class CheckTask extends ExecutorTask<Void, UpdateResult> {
		private final Context context;
		private final Callback callback;

		private CheckTask(Context context, Callback callback) {
			this.context = context.getApplicationContext();
			this.callback = callback;
		}

		@Override
		protected UpdateResult run() {
			return check(context);
		}

		@Override
		protected void onComplete(UpdateResult result) {
			if (callback != null) {
				callback.onResult(result);
			}
		}
	}

	public static CheckTask checkAsync(Context context, Callback callback) {
		CheckTask task = new CheckTask(context, callback);
		task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
		return task;
	}

	public static boolean shouldStartAutomaticCheck() {
		return Preferences.isUpdateAutoCheckEnabled();
	}

	public static boolean shouldShowAutomatically(UpdateResult result, long now) {
		if (result == null || result.status != UpdateResult.Status.UPDATE_AVAILABLE &&
				result.status != UpdateResult.Status.UPDATE_UNAVAILABLE &&
				result.status != UpdateResult.Status.RELEASE_FOUND) {
			return false;
		}
		int versionCode = result.getPreferenceVersionCode();
		if (Preferences.getUpdateSkippedVersionCode() == versionCode) {
			return false;
		}
		return now >= Preferences.getUpdateRemindAfterTime();
	}

	private static UpdateResult check(Context context) {
		long now = System.currentTimeMillis();
		Preferences.setUpdateLastCheckTime(now);
		Exception lastException = null;
		boolean hasSource = false;
		String manifestUrl = BuildConfig.UPDATE_MANIFEST_URL;
		if (!StringUtils.isEmpty(manifestUrl)) {
			hasSource = true;
			try {
				UpdateResult result = UpdateManifest.parse(downloadString(manifestUrl),
						context.getPackageName(), BuildConfig.VERSION_CODE);
				saveResult(result);
				return result;
			} catch (UpdateManifest.UnknownSchemaException | JSONException | IOException |
					RuntimeException e) {
				lastException = e;
			}
		}
		String releasesUrl = BuildConfig.UPDATE_GITHUB_RELEASES_URL;
		String latestReleaseUrl = BuildConfig.UPDATE_GITHUB_LATEST_RELEASE_URL;
		if (!StringUtils.isEmpty(releasesUrl) || !StringUtils.isEmpty(latestReleaseUrl)) {
			hasSource = true;
			try {
				UpdateResult result = GitHubReleaseFallback.check(releasesUrl, latestReleaseUrl);
				if (result != null) {
					saveResult(result);
					return result;
				}
			} catch (Exception e) {
				lastException = e;
			}
		}
		UpdateResult result = UpdateResult.error(hasSource ? describeError(lastException)
				: "Update sources are not configured");
		saveResult(result);
		return result;
	}

	private static void saveResult(UpdateResult result) {
		if (result.source != null) {
			Preferences.setUpdateLastSource(result.source.value);
		}
		if (result.status == UpdateResult.Status.ERROR) {
			Preferences.setUpdateLastError(result.errorMessage);
		} else {
			Preferences.setUpdateLastError(null);
		}
	}

	private static String describeError(Exception exception) {
		if (exception == null) {
			return "Unknown error";
		}
		String message = exception.getMessage();
		if (!StringUtils.isEmpty(message)) {
			return message;
		}
		return exception.getClass().getSimpleName();
	}

	static String downloadString(String urlString) throws IOException {
		urlString = normalizeUrl(urlString);
		URLConnection rawConnection = new URL(urlString).openConnection();
		rawConnection.setConnectTimeout(TIMEOUT_MS);
		rawConnection.setReadTimeout(TIMEOUT_MS);
		rawConnection.setRequestProperty("User-Agent", USER_AGENT);
		rawConnection.setRequestProperty("Accept", "application/json");
		HttpURLConnection httpConnection = rawConnection instanceof HttpURLConnection
				? (HttpURLConnection) rawConnection : null;
		try {
			if (httpConnection != null) {
				httpConnection.setInstanceFollowRedirects(true);
				int responseCode = httpConnection.getResponseCode();
				if (responseCode == 403 || responseCode == 429) {
					throw new IOException("HTTP " + responseCode);
				}
				if (responseCode < 200 || responseCode >= 300) {
					throw new IOException("HTTP " + responseCode);
				}
			}
			try (InputStream input = rawConnection.getInputStream();
					ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				IOUtils.copyStream(input, output);
				return output.toString("UTF-8");
			}
		} finally {
			if (httpConnection != null) {
				httpConnection.disconnect();
			}
		}
	}

	private static String normalizeUrl(String url) {
		if (url.startsWith("//")) {
			return "https:" + url;
		}
		return url;
	}
}
