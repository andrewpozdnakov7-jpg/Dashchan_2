package com.mishiranu.dashchan.chan.ejchan;

import android.net.Uri;
import chan.content.Chan;
import chan.http.CookieBuilder;
import chan.http.FirewallResolver;
import chan.http.HttpException;
import chan.http.HttpResponse;
import chan.util.StringUtils;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves the JavaScript proof-of-work page served by Anubis in front of the Ejchan API. */
public class EjchanAnubisResolver extends FirewallResolver {
	private static final String COOKIE_STORAGE_KEY = "ejchan_anubis";
	private static final String ANUBIS_PATH = "/.within.website/";

	private static boolean isAnubisCookie(String name) {
		return name != null && name.toLowerCase(Locale.US).contains("anubis");
	}

	private static boolean isAuthorizationCookie(String name) {
		if (!isAnubisCookie(name)) return false;
		String lower = name.toLowerCase(Locale.US);
		return !lower.contains("verification") && !lower.endsWith("-test");
	}

	private static String collectAnubisCookies(Map<String, String> cookies) {
		CookieBuilder builder = new CookieBuilder();
		boolean authorized = false;
		for (Map.Entry<String, String> entry : cookies.entrySet()) {
			if (isAnubisCookie(entry.getKey()) && !StringUtils.isEmpty(entry.getValue())) {
				builder.append(entry.getKey(), entry.getValue());
				authorized |= isAuthorizationCookie(entry.getKey());
			}
		}
		return authorized ? builder.build() : null;
	}

	private static boolean isAnubisPage(String responseText) {
		if (StringUtils.isEmpty(responseText)) return false;
		String lower = responseText.toLowerCase(Locale.US);
		return lower.contains(ANUBIS_PATH) || lower.contains("anubis_challenge")
				|| lower.contains("techaro.lol-anubis");
	}

	private static String getHeader(HttpResponse response, String name) {
		for (Map.Entry<String, List<String>> entry : response.getHeaderFields().entrySet()) {
			if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
				return entry.getValue().get(0);
			}
		}
		return null;
	}

	private static class WebViewClient extends FirewallResolver.WebViewClient<String> {
		public WebViewClient() {
			super("Anubis");
		}

		@Override
		public boolean onPageFinished(Uri uri, Map<String, String> cookies, String title) {
			String cookie = collectAnubisCookies(cookies);
			if (cookie != null) {
				setResult(cookie);
				return true;
			}
			return false;
		}

		@Override
		public boolean onLoad(Uri initialUri, Uri uri) {
			if (initialUri == null || uri == null || !StringUtils.equals(initialUri.getHost(), uri.getHost())) {
				return false;
			}
			String path = StringUtils.emptyIfNull(uri.getPath());
			return path.equals(StringUtils.emptyIfNull(initialUri.getPath())) || path.startsWith(ANUBIS_PATH);
		}
	}

	private static class Exclusive implements FirewallResolver.Exclusive {
		@Override
		public boolean resolve(Session session, Key key) throws CancelException, InterruptedException {
			String cookie = session.resolveWebView(new WebViewClient());
			if (StringUtils.isEmpty(cookie)) return false;
			Chan chan = session.getChan();
			chan.configuration.storeCookie(key.formatKey(getStorageKey(session.getUri().getHost())), cookie,
					key.formatTitle("Anubis (Ejchan)"));
			chan.configuration.commit();
			return true;
		}
	}

	private static String getStorageKey(String host) {
		return COOKIE_STORAGE_KEY + "_" + StringUtils.emptyIfNull(host).toLowerCase(Locale.US);
	}

	private static Exclusive.Key toKey(Session session) {
		return session.getKey(Identifier.Flag.USER_AGENT);
	}

	@Override
	public CheckResponseResult checkResponse(Session session, HttpResponse response) throws HttpException {
		String contentType = getHeader(response, "Content-Type");
		if (contentType == null || contentType.toLowerCase(Locale.US).startsWith("text/html")) {
			String responseText = response.readString();
			if (isAnubisPage(responseText)) {
				return new CheckResponseResult(toKey(session), new Exclusive()).setRetransmitOnSuccess(true);
			}
		}
		return null;
	}

	@Override
	public void collectCookies(Session session, CookieBuilder cookieBuilder) {
		String host = session.getChan().locator.getPreferredHost();
		String cookie = session.getChanConfiguration().getCookie(toKey(session).formatKey(getStorageKey(host)));
		if (!StringUtils.isEmpty(cookie)) cookieBuilder.append(cookie);
	}
}
