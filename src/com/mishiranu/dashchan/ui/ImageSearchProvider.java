package com.mishiranu.dashchan.ui;

import android.net.Uri;
import androidx.annotation.StringRes;
import com.mishiranu.dashchan.R;

final class ImageSearchProvider {
	private static final ImageSearchProvider[] PROVIDERS = {
			new ImageSearchProvider(R.string.search_image_google_lens,
					"lens.google.com", "uploadbyurl", "url", false, false),
			new ImageSearchProvider(R.string.search_image_yandex,
					"yandex.ru", "images/search", "url", false, true, "rpt", "imageview")
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
	private final boolean uploadLocal;
	private final String[] queryParameters;

	private ImageSearchProvider(@StringRes int titleResId, String host, String path, String urlParameter,
			boolean preferPreview, boolean uploadLocal, String... queryParameters) {
		if (queryParameters.length % 2 != 0) {
			throw new IllegalArgumentException("Query parameters must be key-value pairs");
		}
		this.titleResId = titleResId;
		this.host = host;
		this.path = path;
		this.urlParameter = urlParameter;
		this.preferPreview = preferPreview;
		this.uploadLocal = uploadLocal;
		this.queryParameters = queryParameters.clone();
	}

	private Uri getSourceUri(Uri imageUri, Uri previewUri) {
		return preferPreview && previewUri != null ? previewUri : imageUri;
	}

	public boolean requiresUpload(Uri imageUri, Uri previewUri) {
		if (!uploadLocal) {
			return false;
		}
		String scheme = getSourceUri(imageUri, previewUri).getScheme();
		return !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme);
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
