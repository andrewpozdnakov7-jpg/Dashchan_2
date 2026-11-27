package com.mishiranu.dashchan.chan.arhivach;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.content.AdvancedPreferences;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.net.UserAgentProvider;
import com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Continues the session opened by the user in the visible Arhivach browser.
 *
 * <p>The user passes the site verification in a regular visible WebView. Its clearance
 * cookie is stored under the key the standard CloudFlare resolver reads, so native
 * requests carry it. Nothing is resolved in the background.</p>
 *
 * <p>The stored copy is deliberately short-lived and revocable: it expires after
 * {@link #TTL_MS}, it is deleted on a repeated challenge, on an explicit reset and when
 * the WebView cookies are cleared. Ending of the browser session itself is not observed,
 * so the TTL is the upper bound of the copy lifetime.</p>
 */
public final class ArhivachBrowserSession {
	public static final String TAG = "ArhivachBridge";
	public static final String CHAN_NAME = "arhivach";
	public static final long TTL_MS = 7L * 24L * 60L * 60L * 1000L;

	private static final String CLEARANCE_COOKIE = "cf_clearance";
	private static final String PREFS_NAME = "arhivach.bridge";
	private static final String PREF_SAVED_AT = "clearance.savedAt";
	private static final String SITE_URL = "https://arhivach.vc/";

	private ArhivachBrowserSession() {}

	public static void log(String message) {
		Log.i(TAG, message);
		try {
			if (PostingFormDiagnostics.isRecording()) {
				PostingFormDiagnostics.recordNetwork(PostingFormDiagnostics.captureId(), "arhivach " + message);
			}
		} catch (RuntimeException e) {
			// Diagnostics must never break the request path.
		}
	}

	public static String fingerprint(String value) {
		if (StringUtils.isEmpty(value)) return "none";
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(8);
			for (int i = 0; i < 4; i++) builder.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
			return builder.toString();
		} catch (Exception e) {
			return "error";
		}
	}

	private static SharedPreferences preferences() {
		return MainApplication.getInstance().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	private static String storedClearance() {
		Chan chan = Chan.get(CHAN_NAME);
		return chan != null && chan.configuration != null
				? chan.configuration.getCookie(CLEARANCE_COOKIE) : null;
	}

	private static boolean isExpired() {
		long savedAt = preferences().getLong(PREF_SAVED_AT, 0L);
		return savedAt > 0L && System.currentTimeMillis() - savedAt > TTL_MS;
	}

	/** Drops the stored copy. Does not touch the WebView cookies. */
	public static void clearStoredClearance(String reason) {
		Chan chan = Chan.get(CHAN_NAME);
		if (chan != null && chan.configuration != null) {
			chan.configuration.storeCookie(CLEARANCE_COOKIE, null, null);
			chan.configuration.commit();
		}
		preferences().edit().remove(PREF_SAVED_AT).apply();
		log("clearance_cleared reason=" + reason);
	}

	/** Explicit user action: clears both the stored copy and the site cookies in the WebView. */
	public static void resetAccess() {
		clearStoredClearance("user_reset");
		try {
			CookieManager manager = CookieManager.getInstance();
			manager.setCookie(SITE_URL, CLEARANCE_COOKIE + "=; Max-Age=0; Path=/");
			manager.flush();
			log("webview_cookies_cleared");
		} catch (RuntimeException e) {
			log("webview_cookies_clear_failed " + e.getClass().getSimpleName());
		}
	}

	public static String cookieStatus() {
		String cookie = storedClearance();
		if (StringUtils.isEmpty(cookie)) return "cookie=none";
		if (isExpired()) {
			clearStoredClearance("ttl_expired");
			return "cookie=none expired=true";
		}
		return "cookie=present len=" + cookie.length() + " fp=" + fingerprint(cookie);
	}

	public static String userAgentStatus() {
		String webView = UserAgentProvider.getInstance().getUserAgent();
		String nativeUserAgent = AdvancedPreferences.getUserAgent(CHAN_NAME);
		return "uaWebViewFp=" + fingerprint(webView) + " uaNativeFp=" + fingerprint(nativeUserAgent)
				+ " same=" + (webView != null && webView.equals(nativeUserAgent));
	}

	public static boolean isSite(Uri uri) {
		if (uri == null) return false;
		String host = uri.getHost();
		return "arhivach.vc".equals(host) || "www.arhivach.vc".equals(host);
	}

	/** Copies the clearance of the visible browser session. Must be called from the UI thread. */
	public static boolean storeCookies(String url) {
		if (url == null) return false;
		Uri uri = Uri.parse(url);
		if (!isSite(uri)) return false;
		String clearance = findCookie(CookieManager.getInstance().getCookie(url), CLEARANCE_COOKIE);
		log("webview_page host=" + uri.getHost()
				+ (StringUtils.isEmpty(clearance) ? " clearance=none"
				: " clearance=present len=" + clearance.length() + " fp=" + fingerprint(clearance)));
		if (StringUtils.isEmpty(clearance)) return false;
		Chan chan = Chan.get(CHAN_NAME);
		if (chan == null || !CHAN_NAME.equals(chan.name)) return false;
		String stored = chan.configuration.getCookie(CLEARANCE_COOKIE);
		if (clearance.equals(stored) && !isExpired()) {
			log("clearance_same len=" + clearance.length() + " fp=" + fingerprint(clearance));
			return false;
		}
		chan.configuration.storeCookie(CLEARANCE_COOKIE, clearance, "CloudFlare");
		chan.configuration.commit();
		preferences().edit().putLong(PREF_SAVED_AT, System.currentTimeMillis()).apply();
		log("clearance_stored len=" + clearance.length() + " fp=" + fingerprint(clearance)
				+ " ttlDays=" + (TTL_MS / (24L * 60L * 60L * 1000L))
				+ " replaced=" + !StringUtils.isEmpty(stored));
		return true;
	}

	private static String findCookie(String cookies, String name) {
		if (StringUtils.isEmpty(cookies)) return null;
		String prefix = name + '=';
		for (String pair : cookies.split(";\\s*")) {
			if (pair.startsWith(prefix) && pair.length() > prefix.length()) {
				return pair.substring(prefix.length());
			}
		}
		return null;
	}
}
