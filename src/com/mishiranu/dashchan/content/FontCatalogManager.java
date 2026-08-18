package com.mishiranu.dashchan.content;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import chan.content.Chan;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class FontCatalogManager {
	private static final int MAX_CATALOG_ITEMS = 100;
	private static final long RESTORE_RETRY_INTERVAL_MS = 60_000L;
	private static final AtomicBoolean RESTORE_RUNNING = new AtomicBoolean();
	private static volatile long lastRestoreAttempt;

	public static final class CatalogFont {
		public final String id;
		public final String title;
		public final String license;
		public final Uri sourceUri;
		public final Uri licenseUri;
		public final Uri fileUri;
		public final String sha256;
		public final long fileSize;

		private CatalogFont(String id, String title, String license, Uri sourceUri, Uri licenseUri,
				Uri fileUri, String sha256, long fileSize) {
			this.id = id;
			this.title = title;
			this.license = license;
			this.sourceUri = sourceUri;
			this.licenseUri = licenseUri;
			this.fileUri = fileUri;
			this.sha256 = sha256;
			this.fileSize = fileSize;
		}
	}

	public interface RestoreCallback {
		void onComplete(boolean restored);
	}

	private FontCatalogManager() {}

	public static List<CatalogFont> readCatalog(HttpHolder holder) throws HttpException, JSONException {
		Uri catalogUri = Uri.parse(BuildConfig.URI_FONTS);
		if (!isHttps(catalogUri)) {
			throw new JSONException("Invalid catalog URL");
		}
		JSONObject root = new JSONObject(new HttpRequest(catalogUri, holder).perform().readString());
		if (root.optInt("schemaVersion") != 1) {
			throw new JSONException("Unsupported schema version");
		}
		JSONArray array = root.getJSONArray("fonts");
		if (array.length() > MAX_CATALOG_ITEMS) {
			throw new JSONException("Catalog is too large");
		}
		ArrayList<CatalogFont> fonts = new ArrayList<>(array.length());
		HashSet<String> ids = new HashSet<>();
		for (int i = 0; i < array.length(); i++) {
			JSONObject object = array.getJSONObject(i);
			String id = object.getString("id");
			String title = object.getString("title");
			String license = object.getString("license");
			Uri sourceUri = Uri.parse(object.getString("sourceUrl"));
			Uri licenseUri = Uri.parse(object.getString("licenseUrl"));
			Uri fileUri = Uri.parse(object.getString("fileUrl"));
			String sha256 = object.getString("sha256").toLowerCase(Locale.US);
			long fileSize = object.getLong("fileSizeBytes");
			String path = fileUri.getPath();
			if (!id.matches("[a-z0-9][a-z0-9_]{0,63}") || StringUtils.isEmpty(title)
					|| StringUtils.isEmpty(license) || !isHttps(sourceUri) || !isHttps(licenseUri)
					|| !isHttps(fileUri) || path == null
					|| !(path.endsWith(".ttf") || path.endsWith(".otf"))
					|| !sha256.matches("[a-f0-9]{64}") || fileSize <= 0L
					|| fileSize > FontManager.MAX_FONT_SIZE || !ids.add(id)) {
				throw new JSONException("Invalid font entry");
			}
			fonts.add(new CatalogFont(id, title, license, sourceUri, licenseUri, fileUri,
					sha256, fileSize));
		}
		return fonts;
	}

	public static void downloadAndInstall(Context context, HttpHolder holder, CatalogFont font)
			throws HttpException, IOException {
		downloadAndInstall(context, holder, font, null);
	}

	private static void downloadAndInstall(Context context, HttpHolder holder, CatalogFont font,
			String requiredSelection) throws HttpException, IOException {
		HttpResponse response = null;
		try {
			response = new HttpRequest(font.fileUri, holder).perform();
			try (InputStream input = response.open()) {
				FontManager.installCatalogFont(context, input, font.fileSize, font.sha256,
						font.id, font.title, requiredSelection);
			}
		} finally {
			if (response != null) {
				response.cleanupAndDisconnect();
			}
		}
	}

	public static void restoreSelectedFontAsync(Context context, RestoreCallback callback) {
		Context applicationContext = context.getApplicationContext();
		String selectedId = FontManager.getSelectedMissingCatalogId(applicationContext);
		long now = SystemClock.elapsedRealtime();
		if (selectedId == null || (lastRestoreAttempt != 0L
				&& now - lastRestoreAttempt < RESTORE_RETRY_INTERVAL_MS)
				|| !RESTORE_RUNNING.compareAndSet(false, true)) {
			return;
		}
		lastRestoreAttempt = now;
		ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
			boolean restored = false;
			HttpHolder holder = new HttpHolder(Chan.getFallback());
			try (HttpHolder.Use ignored = holder.use()) {
				for (CatalogFont font : readCatalog(holder)) {
					if (selectedId.equals(font.id)) {
						String requiredSelection = FontManager.getCatalogPreferenceId(selectedId);
						downloadAndInstall(applicationContext, holder, font, requiredSelection);
						restored = requiredSelection.equals(Preferences.getApplicationFont());
						break;
					}
				}
			} catch (HttpException | IOException | JSONException | RuntimeException e) {
				// Keep the catalog selection unchanged and retry on a later application start.
			} finally {
				RESTORE_RUNNING.set(false);
			}
			boolean result = restored;
			ConcurrentUtils.HANDLER.post(() -> callback.onComplete(result));
		});
	}

	private static boolean isHttps(Uri uri) {
		return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && !StringUtils.isEmpty(uri.getHost());
	}
}
