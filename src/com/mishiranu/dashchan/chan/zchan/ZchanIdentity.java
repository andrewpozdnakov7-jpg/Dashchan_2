package com.mishiranu.dashchan.chan.zchan;

import chan.content.ApiException;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.MultipartEntity;
import chan.http.SimpleEntity;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import org.json.JSONException;
import org.json.JSONObject;

final class ZchanIdentity {
	private static final String STORAGE_ZID = "zchan_zid";

	private final ZchanChanConfiguration configuration;
	private final ZchanChanLocator locator;

	private String zid;

	ZchanIdentity(ZchanChanConfiguration configuration, ZchanChanLocator locator) {
		this.configuration = configuration;
		this.locator = locator;
	}

	String obtainZid(HttpRequest.Preset preset) throws HttpException, ApiException,
			InvalidResponseException {
		if (StringUtils.isEmpty(zid)) zid = configuration.getCookie(STORAGE_ZID);
		if (!StringUtils.isEmpty(zid)) return zid;

		try {
			ZchanChanConfiguration.IdentityProfile profile = configuration.obtainIdentityProfile();
			JSONObject requestObject = new JSONObject();
			requestObject.put("device", createDeviceInfo(profile));
			requestObject.put("app", createAppInfo(profile));
			requestObject.put("source", profile.source);
			requestObject.put("email", "");

			SimpleEntity entity = new SimpleEntity();
			entity.setData(requestObject.toString());
			entity.setContentType("application/json; charset=UTF-8");
			HttpResponse response = new HttpRequest(locator.createRegistrationApiUri(), preset)
					.setPostMethod(entity).addHeader("Accept", "application/json")
					.setSuccessOnly(false).perform();
			String responseText = response.readString();
			if (response.getResponseCode() < 200 || response.getResponseCode() >= 300) {
				throw new ApiException(readError(responseText,
						"Zchan registration failed (HTTP " + response.getResponseCode() + ")"));
			}
			JSONObject responseObject = new JSONObject(responseText);
			zid = StringUtils.nullIfEmpty(responseObject.optString("zid", null));
			if (StringUtils.isEmpty(zid)) {
				throw new ApiException("Zchan did not issue an ID for Slooop. Posting may require approval "
						+ "from the Zchan administration.");
			}
			configuration.storeCookie(STORAGE_ZID, zid, "Zchan ID");
			return zid;
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	void addClientFields(MultipartEntity entity, String zid) throws InvalidResponseException {
		try {
			ZchanChanConfiguration.IdentityProfile profile = configuration.obtainIdentityProfile();
			entity.add("zid", zid);
			addObjectFields(entity, "device", createDeviceInfo(profile));
			addObjectFields(entity, "app", createAppInfo(profile));
			entity.add("source", profile.source);
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	void invalidateZid() {
		zid = null;
		configuration.storeCookie(STORAGE_ZID, null, null);
	}

	private static JSONObject createDeviceInfo(ZchanChanConfiguration.IdentityProfile profile) throws JSONException {
		JSONObject device = new JSONObject();
		device.put("uuid", profile.uuid);
		device.put("name", profile.name);
		device.put("brand", profile.brand);
		device.put("model", profile.model);
		device.put("os_name", "android");
		device.put("os_version", profile.osVersion);
		device.put("is_physical", true);
		device.put("fingerprint", profile.fingerprint);
		device.put("timezone", profile.timezone);
		device.put("lang", profile.language);
		return device;
	}

	private static JSONObject createAppInfo(ZchanChanConfiguration.IdentityProfile profile) throws JSONException {
		JSONObject app = new JSONObject();
		app.put("name", "Slooop");
		app.put("version", profile.appVersion);
		app.put("build", Integer.toString(BuildConfig.VERSION_CODE));
		return app;
	}

	String obtainLanguage() {
		return configuration.obtainIdentityProfile().language;
	}

	private static void addObjectFields(MultipartEntity entity, String name, JSONObject object)
			throws JSONException {
		for (java.util.Iterator<String> iterator = object.keys(); iterator.hasNext();) {
			String key = iterator.next();
			entity.add(name + "[" + key + "]", String.valueOf(object.get(key)));
		}
	}

	static String readError(String responseText, String fallback) {
		try {
			JSONObject object = new JSONObject(StringUtils.emptyIfNull(responseText));
			String error = StringUtils.nullIfEmpty(object.optString("error", null));
			if (StringUtils.isEmpty(error)) error = StringUtils.nullIfEmpty(object.optString("message", null));
			return !StringUtils.isEmpty(error) ? StringUtils.clearHtml(error) : fallback;
		} catch (JSONException e) {
			return fallback;
		}
	}
}
