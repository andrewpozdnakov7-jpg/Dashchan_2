package com.mishiranu.dashchan.content.update;

import android.os.Build;
import chan.util.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;

public class UpdateManifest {
	public static class UnknownSchemaException extends Exception {}

	public static UpdateResult parse(String json, String packageName, int currentVersionCode)
			throws JSONException, UnknownSchemaException {
		JSONObject root = new JSONObject(json);
		if (root.optInt("schemaVersion", -1) != 1) {
			throw new UnknownSchemaException();
		}
		JSONObject app = root.getJSONObject("app");
		String manifestPackageName = app.optString("packageName", null);
		if (!packageName.equals(manifestPackageName)) {
			return UpdateResult.noUpdate(UpdateResult.Source.PRIMARY_JSON);
		}
		int versionCode = app.getInt("versionCode");
		if (versionCode <= currentVersionCode) {
			return UpdateResult.noUpdate(UpdateResult.Source.PRIMARY_JSON);
		}
		String versionName = app.optString("versionName", null);
		int minSdk = app.optInt("minSdk", 0);
		String title = app.optString("title", null);
		String summary = app.optString("summary", null);
		String releaseNotesUrl = app.optString("releaseNotesUrl", null);
		String downloadPageUrl = app.optString("downloadPageUrl", null);
		boolean critical = app.optBoolean("critical", false);
		if (StringUtils.isEmpty(downloadPageUrl)) {
			downloadPageUrl = releaseNotesUrl;
		}
		if (minSdk > Build.VERSION.SDK_INT) {
			return UpdateResult.unavailable(UpdateResult.Source.PRIMARY_JSON, versionCode, versionName, minSdk,
					title, summary, releaseNotesUrl, downloadPageUrl, critical);
		}
		return UpdateResult.available(UpdateResult.Source.PRIMARY_JSON, versionCode, versionName, minSdk,
				title, summary, releaseNotesUrl, downloadPageUrl, critical);
	}
}
