package com.mishiranu.dashchan.content.update;

import android.os.Build;
import chan.util.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class UpdateManifest {
	public static class UnknownSchemaException extends Exception {}

	public static UpdateResult parse(String json, String packageName, int currentVersionCode)
			throws JSONException, UnknownSchemaException {
		JSONObject root = new JSONObject(json);
		if (!root.has("schemaVersion") && root.optJSONArray("client") != null) {
			return parseLegacyClient(root.getJSONArray("client"), currentVersionCode);
		}
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

	private static UpdateResult parseLegacyClient(JSONArray clients, int currentVersionCode) throws JSONException {
		JSONObject newest = null;
		JSONObject compatible = null;
		for (int i = 0; i < clients.length(); i++) {
			JSONObject client = clients.getJSONObject(i);
			int code = client.getInt("code");
			if (code <= 0) throw new JSONException("Invalid client version code");
			int maxSdk = client.optInt("maxSdk", 0);
			if (maxSdk > 0 && maxSdk < Build.VERSION.SDK_INT) continue;
			if (newest == null || code > newest.getInt("code")) newest = client;
			if (client.optInt("minSdk", 0) <= Build.VERSION.SDK_INT &&
					(compatible == null || code > compatible.getInt("code"))) compatible = client;
		}
		JSONObject client = compatible != null ? compatible : newest;
		if (client == null || client.getInt("code") <= currentVersionCode) {
			return UpdateResult.noUpdate(UpdateResult.Source.PRIMARY_JSON);
		}
		int code = client.getInt("code");
		String name = client.optString("name", null);
		int minSdk = client.optInt("minSdk", 0);
		String notes = client.optString("releaseNotesUrl", null);
		String page = client.optString("downloadPageUrl", notes);
		if (StringUtils.isEmpty(page)) page = client.optString("source", null);
		if (minSdk > Build.VERSION.SDK_INT) {
			return UpdateResult.unavailable(UpdateResult.Source.PRIMARY_JSON, code, name, minSdk,
					null, null, notes, page, false);
		}
		return UpdateResult.available(UpdateResult.Source.PRIMARY_JSON, code, name, minSdk,
				null, null, notes, page, false);
	}
}
