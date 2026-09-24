package com.mishiranu.dashchan.chan.kohlchan;

import chan.content.Chan;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.MultipartEntity;
import chan.util.CommonUtils;
import org.json.JSONException;
import org.json.JSONObject;

/** Only the site may issue/activate the permission; CAPTCHA is entered by the user on its page. */
public final class KohlchanAccess {
	public static final String ORIGIN = "https://kohlchan.net";
	public static final String PAGE = ORIGIN + "/blockBypass.js";
	private static final String COOKIE = "bypass";

	private KohlchanAccess() {}

	public static String checkedCookie(String value) {
		if (value == null || value.isEmpty() || value.length() > 8192) return null;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c <= 32 || c >= 127 || c == ';') return null;
		}
		return value;
	}

	public static String getCookie(Chan chan) {
		return checkedCookie(chan.configuration.getCookie(COOKIE));
	}

	public static void storeCookie(Chan chan, String cookie) {
		chan.configuration.storeCookie(COOKIE, checkedCookie(cookie), "Kohlchan");
	}

	public static final class State {
		public final boolean valid;
		public final int mode;

		private State(boolean valid, int mode) {
			this.valid = valid;
			this.mode = mode;
		}
	}

	public static State check(Chan chan, HttpHolder holder, String cookie)
			throws HttpException, InvalidResponseException {
		HttpRequest request = new HttpRequest(chan.locator.buildQuery("blockBypass.js", "json", "1"), holder)
				.setPostMethod(new MultipartEntity()).setRedirectHandler(HttpRequest.RedirectHandler.NONE)
				.addHeader("Origin", ORIGIN).addHeader("Referer", PAGE);
		cookie = checkedCookie(cookie);
		if (cookie != null) request.addCookie(COOKIE, cookie);
		HttpResponse response;
		try {
			response = request.perform();
		} catch (HttpException e) {
			trace("blockBypass", e.getResponseCode(), "http_error", cookie != null);
			throw e;
		}
		try {
			JSONObject object = new JSONObject(response.readString());
			trace("blockBypass", response.getResponseCode(), object.optString("status"), cookie != null);
			if (!"ok".equals(object.optString("status"))) throw new InvalidResponseException();
			JSONObject data = object.getJSONObject("data");
			int mode = data.getInt("mode");
			if (mode < 0 || mode > 2) throw new InvalidResponseException();
			State state = new State(data.getBoolean("valid"), mode);
			CommonUtils.writeLog("KohlchanAccess", "permission", "valid=" + state.valid, "mode=" + mode);
			return state;
		} catch (JSONException e) {
			trace("blockBypass", response.getResponseCode(), "invalid_json", cookie != null);
			// Do not include JSON parser exceptions: they may embed response content.
			throw new InvalidResponseException();
		}
	}

	public static void trace(String endpoint, int httpCode, String status, boolean hasPermission) {
		// Status is allowlisted: never log payloads, CAPTCHA input, cookies or user text.
		switch (status) {
			case "ok": case "error": case "bypassable": case "hashcash": case "banned":
			case "hashBan": case "maintenance": case "http_error": case "invalid_json": break;
			default: status = "other";
		}
		CommonUtils.writeLog("KohlchanPosting", endpoint, "http=" + httpCode,
				"status=" + status, "permissionPresent=" + hasPermission);
	}
}
