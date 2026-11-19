package com.mishiranu.dashchan.chan.soyjak;

import android.net.Uri;
import android.webkit.CookieManager;
import chan.content.Chan;
import chan.util.StringUtils;

/**
 * Continues the session opened by the user in the visible Soyjak browser.
 *
 * <p>The user solves the site verification in a regular, visible WebView. Its clearance
 * cookie is kept under the same key that the standard CloudFlare resolver reads, so native
 * requests carry it and the reader does not ask to open the browser for every resource.
 * Nothing is resolved in the background: without a successful visible verification the
 * stored cookie stays empty and the regular browser challenge is shown.</p>
 *
 * <p>Every step is reported to {@link SoyjakDiagnostics} without the cookie value, so a
 * device test can show whether the clearance was actually captured.</p>
 */
public final class SoyjakBrowserSession {
	private SoyjakBrowserSession() {}

	/** Copies the clearance of the visible browser session. Must be called from the UI thread. */
	public static boolean storeCookies(String url) {
		if (url == null) return false;
		Uri uri = Uri.parse(url);
		if (!SoyjakReaderPage.isSite(uri)) return false;
		String clearance = findCookie(CookieManager.getInstance().getCookie(url), SoyjakDiagnostics.CLEARANCE_COOKIE);
		SoyjakDiagnostics.event("webview_page", "route=" + SoyjakDiagnostics.route(uri)
				+ " clearance=" + (StringUtils.isEmpty(clearance) ? "none" : "present"));
		if (StringUtils.isEmpty(clearance)) return false;
		Chan chan = Chan.get(SoyjakDiagnostics.CHAN_NAME);
		if (chan == null || !SoyjakDiagnostics.CHAN_NAME.equals(chan.name)) return false;
		String stored = chan.configuration.getCookie(SoyjakDiagnostics.CLEARANCE_COOKIE);
		if (clearance.equals(stored)) {
			SoyjakDiagnostics.event("clearance_same", "len=" + clearance.length()
					+ " fp=" + SoyjakDiagnostics.fingerprint(clearance));
			return false;
		}
		chan.configuration.storeCookie(SoyjakDiagnostics.CLEARANCE_COOKIE, clearance, "CloudFlare");
		chan.configuration.commit();
		SoyjakDiagnostics.onClearanceStored(clearance);
		SoyjakDiagnostics.event("clearance_stored", "len=" + clearance.length()
				+ " fp=" + SoyjakDiagnostics.fingerprint(clearance)
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
