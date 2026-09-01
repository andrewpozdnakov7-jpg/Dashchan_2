package com.mishiranu.dashchan.content.net.firewall;

import android.net.Uri;
import chan.content.Chan;
import chan.http.CookieBuilder;
import chan.http.FirewallResolver;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.util.StringUtils;
import com.mishiranu.dashchan.content.Preferences;
import java.util.List;
import java.util.Map;

public class StormWallResolver extends FirewallResolver {
	private static final String COOKIE_STORMWALL = "swp_token";

	private static class CookieResult {
		public final String cookie;
		public final Uri uri;

		public CookieResult(String cookie, Uri uri) {
			this.cookie = cookie;
			this.uri = uri;
		}
	}

	private static class WebViewClient extends FirewallResolver.WebViewClient<CookieResult> {
		private volatile boolean wasChecked = false;
		private volatile boolean wasReloaded = false;

		public WebViewClient() {
			super("StormWall");
		}

		@Override
		public boolean onPageFinished(Uri uri, Map<String, String> cookies, String title) {
			if (wasChecked) {
				wasChecked = false;
				wasReloaded = true;
			} else if (wasReloaded) {
				String cookie = cookies.get(COOKIE_STORMWALL);
				setResult(cookie != null ? new CookieResult(cookie, uri) : null);
				wasReloaded = false;
				return true;
			}
			return false;
		}

		@Override
		public boolean onLoad(Uri initialUri, Uri uri) {
			if ("static.stormwall.pro".equals(uri.getHost())) {
				wasChecked = true;
				return true;
			}
			String path = uri.getPath();
			return path == null || path.isEmpty() || "/".equals(path) || path.equals(initialUri.getPath());
		}
	}

	private static String findAssignment(String source, String name, boolean quotedOnly) {
		String marker = " " + name;
		int fromIndex = 0;
		while (true) {
			int start = source.indexOf(marker, fromIndex);
			if (start < 0) {
				return null;
			}
			int index = start + marker.length();
			while (index < source.length() && source.charAt(index) == ' ') {
				index++;
			}
			if (index >= source.length() || source.charAt(index) != '=') {
				fromIndex = start + marker.length();
				continue;
			}
			index++;
			while (index < source.length() && source.charAt(index) == ' ') {
				index++;
			}
			if (index >= source.length()) {
				return null;
			}
			char quote = source.charAt(index);
			if (quote == '\'' || quote == '\"') {
				int end = source.indexOf(quote, index + 1);
				return end >= 0 ? source.substring(index + 1, end) : null;
			}
			if (quotedOnly) {
				fromIndex = index + 1;
				continue;
			}
			int end = source.indexOf(';', index);
			return source.substring(index, end >= 0 ? end : source.length()).trim();
		}
	}

	private static String calculateCookie(String ce, int ck) {
		StringBuilder result = new StringBuilder();
		String alphabet = "0123456789qwertyuiopasdfghjklzxcvbnm:?!";
		int length = alphabet.length();
		for (int i = 0; i < ce.length(); i++) {
			char c = ce.charAt(i);
			int index = alphabet.indexOf(c);
			result.append(index >= 0 ? alphabet.charAt((index - ((ck + i) % length) + length) % length) : c);
		}
		return result.toString();
	}

	private class Exclusive implements FirewallResolver.Exclusive {
		public final String responseText;

		public Exclusive(String responseText) {
			this.responseText = responseText;
		}

		@Override
		public boolean resolve(Session session, Key key) throws CancelException, HttpException, InterruptedException {
			String ce = StringUtils.emptyIfNull(findAssignment(responseText, "cE", true));
			String ckString = StringUtils.emptyIfNull(findAssignment(responseText, "cK", false));
			if (!ce.isEmpty() && !ckString.isEmpty()) {
				Integer ck = null;
				try {
					ck = Integer.parseInt(ckString);
				} catch (NumberFormatException e) {
					// Ignore
				}
				if (!ce.isEmpty() && ck != null) {
					String calculatedCookie = calculateCookie(ce, ck);
					HttpResponse response = new HttpRequest(session.getUri(), session)
							.setHeadMethod().setSuccessOnly(false)
							.addHeader("User-Agent", session.getIdentifier().userAgent)
							.addCookie(COOKIE_STORMWALL, calculatedCookie)
							.perform();
					try {
						if (!isBlocked(response)) {
							storeCookie(session, key, calculatedCookie, session.getUri());
							return true;
						}
					} finally {
						response.cleanupAndDisconnect();
					}
				}
			}
			CookieResult result = session.resolveWebView(new WebViewClient());
			if (result != null) {
				storeCookie(session, key, result.cookie, result.uri);
				return true;
			}
			return false;
		}
	}

	private boolean isBlocked(HttpResponse response) {
		List<String> headers = response.getHeaderFields().get("X-FireWall-Protection");
		return headers != null && !headers.isEmpty();
	}

	private static Exclusive.Key toKey(Session session) {
		return session.getKey(Identifier.Flag.USER_AGENT);
	}

	@Override
	public CheckResponseResult checkResponse(Session session, HttpResponse response) throws HttpException {
		if (isBlocked(response)) {
			if (session.isResolveRequest()) {
				List<String> contentType = response.getHeaderFields().get("Content-Type");
				if (contentType != null && contentType.size() == 1 && contentType.get(0).startsWith("text/html")) {
					String responseText = response.readString();
					return new CheckResponseResult(session.getKey(), new Exclusive(responseText));
				}
			}
			return new CheckResponseResult(session.getKey(), Exclusive.FAIL);
		}
		return null;
	}

	private void storeCookie(Session session, Exclusive.Key key, String cookie, Uri uri) {
		Chan chan = session.getChan();
		chan.configuration.storeCookie(key.formatKey(COOKIE_STORMWALL), cookie,
				cookie != null ? key.formatTitle("StormWall") : null);
		chan.configuration.commit();
		if (uri != null) {
			String host = uri.getHost();
			if (chan.locator.isConvertableChanHost(host)) {
				chan.locator.setPreferredHost(host);
			}
			Preferences.setUseHttps(chan, "https".equals(uri.getScheme()));
		}
	}

	@Override
	public void collectCookies(Session session, CookieBuilder cookieBuilder) {
		Chan chan = session.getChan();
		Exclusive.Key key = toKey(session);
		String cookie = chan.configuration.getCookie(key.formatKey(COOKIE_STORMWALL));
		if (!StringUtils.isEmpty(cookie)) {
			cookieBuilder.append(COOKIE_STORMWALL, cookie);
		}
	}
}
