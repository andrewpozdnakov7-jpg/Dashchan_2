package com.mishiranu.dashchan.ui;

import android.net.Uri;

final class ImageSearchProvider {
	private static final ImageSearchProvider[] PROVIDERS = {
			new ImageSearchProvider("Google Lens", "lens.google.com", "uploadbyurl", "url", false),
			new ImageSearchProvider("Yandex", "yandex.ru", "images/search", "url", false,
					"rpt", "imageview"),
			new ImageSearchProvider("TinEye", "www.tineye.com", "search", "url", false),
			new ImageSearchProvider("SauceNAO", "saucenao.com", "search.php", "url", false),
			new ImageSearchProvider("iqdb.org", "iqdb.org", null, "url", true),
			new ImageSearchProvider("trace.moe", "trace.moe", null, "url", true)
	};

	public static ImageSearchProvider[] getProviders() {
		return PROVIDERS.clone();
	}

	public final String title;
	private final String host;
	private final String path;
	private final String urlParameter;
	private final boolean preferPreview;
	private final String[] queryParameters;

	private ImageSearchProvider(String title, String host, String path, String urlParameter,
			boolean preferPreview, String... queryParameters) {
		if (queryParameters.length % 2 != 0) {
			throw new IllegalArgumentException("Query parameters must be key-value pairs");
		}
		this.title = title;
		this.host = host;
		this.path = path;
		this.urlParameter = urlParameter;
		this.preferPreview = preferPreview;
		this.queryParameters = queryParameters.clone();
	}

	public Uri buildSearchUri(Uri imageUri, Uri previewUri) {
		Uri sourceUri = preferPreview && previewUri != null ? previewUri : imageUri;
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
