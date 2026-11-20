package com.mishiranu.dashchan.ui;

import android.net.Uri;
import androidx.annotation.StringRes;
import com.mishiranu.dashchan.R;

final class ImageSearchProvider {
	private enum LocalImageHandling {PASS_URL, UPLOAD, HIDE}

	private static final ImageSearchProvider[] PROVIDERS = {
			new ImageSearchProvider(R.string.search_image_google_lens,
					"lens.google.com", "uploadbyurl", "url", false, LocalImageHandling.PASS_URL),
			new ImageSearchProvider(R.string.search_image_yandex,
					"yandex.ru", "images/search", "url", false, LocalImageHandling.UPLOAD, "rpt", "imageview"),
			new ImageSearchProvider(R.string.search_image_tineye,
					"tineye.com", "search/", "url", false, LocalImageHandling.HIDE)
	};

	public static ImageSearchProvider[] getProviders() {
		return PROVIDERS.clone();
	}

	@StringRes
	public final int titleResId;
	private final String host;
	private final String path;
	private final String urlParameter;
	private final boolean preferPreview;
	private final LocalImageHandling localImageHandling;
	private final String[] queryParameters;

	private ImageSearchProvider(@StringRes int titleResId, String host, String path, String urlParameter,
			boolean preferPreview, LocalImageHandling localImageHandling, String... queryParameters) {
		if (queryParameters.length % 2 != 0) {
			throw new IllegalArgumentException("Query parameters must be key-value pairs");
		}
		this.titleResId = titleResId;
		this.host = host;
		this.path = path;
		this.urlParameter = urlParameter;
		this.preferPreview = preferPreview;
		this.localImageHandling = localImageHandling;
		this.queryParameters = queryParameters.clone();
	}

	private Uri getSourceUri(Uri imageUri, Uri previewUri) {
		return preferPreview && previewUri != null ? previewUri : imageUri;
	}

	private static boolean isHttpUri(Uri uri) {
		String scheme = uri != null ? uri.getScheme() : null;
		return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
	}

	public boolean isAvailable(Uri imageUri, Uri previewUri) {
		if (localImageHandling != LocalImageHandling.HIDE) return true;
		Uri sourceUri = getSourceUri(imageUri, previewUri);
		return isHttpUri(sourceUri) && sourceUri.getHost() != null && !sourceUri.getHost().isEmpty()
				&& sourceUri.getUserInfo() == null;
	}

	public boolean requiresUpload(Uri imageUri, Uri previewUri) {
		return localImageHandling == LocalImageHandling.UPLOAD && !isHttpUri(getSourceUri(imageUri, previewUri));
	}

	public Uri buildSearchUri(Uri imageUri, Uri previewUri) {
		Uri sourceUri = getSourceUri(imageUri, previewUri);
		Uri.Builder builder = new Uri.Builder().scheme("https").authority(host);
		if (path != null) {
			builder.appendEncodedPath(path);
		}
		for (int i = 0; i < queryParameters.length; i += 2) {
			builder.appendQueryParameter(queryParameters[i], queryParameters[i + 1]);
		}
		return builder.appendQueryParameter(urlParameter, sourceUri.toString()).build();
	}
}
