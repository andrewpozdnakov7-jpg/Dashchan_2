package com.mishiranu.dashchan.ui;

import android.graphics.Bitmap;
import android.net.Uri;
import android.text.TextUtils;
import chan.http.HttpHolder;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.SimpleEntity;
import com.mishiranu.dashchan.content.model.FileHolder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

final class YandexImageSearch {
	private static final int MAX_IMAGE_SIZE = 1680;
	private static final String USER_AGENT = "Mozilla/5.0";

	private YandexImageSearch() {}

	public static Uri upload(HttpHolder holder, FileHolder fileHolder)
			throws HttpException, IOException, JSONException {
		Bitmap bitmap = fileHolder.readImageBitmap(MAX_IMAGE_SIZE, true, false);
		if (bitmap == null) {
			throw new IOException("Unable to decode image");
		}
		byte[] data;
		try {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
				throw new IOException("Unable to encode image");
			}
			data = output.toByteArray();
		} finally {
			bitmap.recycle();
		}

		Uri uploadUri = new Uri.Builder().scheme("https").authority("yandex.ru")
				.appendPath("images-apphost").appendPath("image-download")
				.appendQueryParameter("cbird", "111")
				.appendQueryParameter("images_avatars_size", "preview")
				.appendQueryParameter("images_avatars_namespace", "images-cbir")
				.build();
		SimpleEntity entity = new SimpleEntity();
		entity.setContentType("image/jpeg");
		entity.setData(data);
		String responseText;
		// A nested holder session suppresses chan firewall cookies. The request carries no account data,
		// persistent identifier, file name, source URI or metadata from the original image.
		try (HttpHolder.Use ignored = holder.use()) {
			responseText = new HttpRequest(uploadUri, holder).setPostMethod(entity)
					.setRedirectHandler(HttpRequest.RedirectHandler.NONE)
					.addHeader("Accept", "application/json")
					.addHeader("User-Agent", USER_AGENT)
					.clearCookies()
					.perform()
					.readString();
		}

		JSONObject response = new JSONObject(responseText);
		String cbirId = response.optString("cbir_id", null);
		String uploadedUrl = response.optString("url", null);
		if (TextUtils.isEmpty(cbirId) || TextUtils.isEmpty(uploadedUrl)) {
			throw new JSONException("Missing image search data");
		}
		Uri uploadedUri = Uri.parse(uploadedUrl);
		if (!"https".equalsIgnoreCase(uploadedUri.getScheme()) || TextUtils.isEmpty(uploadedUri.getHost())) {
			throw new JSONException("Invalid uploaded image URL");
		}
		String encodedPath = uploadedUri.getEncodedPath();
		if (encodedPath != null && encodedPath.endsWith("/preview")) {
			uploadedUri = uploadedUri.buildUpon().encodedPath(encodedPath.substring(0,
					encodedPath.length() - "/preview".length()) + "/orig").build();
		}
		return new Uri.Builder().scheme("https").authority("yandex.ru")
				.appendPath("images").appendPath("search")
				.appendQueryParameter("rpt", "imageview")
				.appendQueryParameter("url", uploadedUri.toString())
				.appendQueryParameter("cbir_id", cbirId)
				.build();
	}
}
