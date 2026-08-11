package com.mishiranu.dashchan.content.net;

import android.app.Application;
import android.webkit.WebSettings;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UserAgentProvider {
	private static final UserAgentProvider INSTANCE = new UserAgentProvider();
	private static final Pattern CHROMIUM_MAJOR_PATTERN = Pattern.compile("(?:Chrome|Chromium)/([0-9]{1,6})",
			Pattern.CASE_INSENSITIVE);
	private static final int FALLBACK_CHROMIUM_MAJOR = 100;
	private static final String USER_AGENT_FORMAT = "Mozilla/5.0 (Linux; Android 10; K; wv) " +
			"AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/%d.0.0.0 Mobile Safari/537.36";

	public static void initialize(Application application) {
		try {
			INSTANCE.userAgent = sanitizeUserAgent(WebSettings.getDefaultUserAgent(application));
		} catch (RuntimeException e) {
			e.printStackTrace();
		}
	}

	public static UserAgentProvider getInstance() {
		return INSTANCE;
	}

	private String userAgent = formatUserAgent(FALLBACK_CHROMIUM_MAJOR);

	private UserAgentProvider() {}

	static String sanitizeUserAgent(String source) {
		int chromiumMajor = FALLBACK_CHROMIUM_MAJOR;
		if (source != null) {
			Matcher matcher = CHROMIUM_MAJOR_PATTERN.matcher(source);
			if (matcher.find()) {
				try {
					int parsed = Integer.parseInt(matcher.group(1));
					if (parsed > 0) {
						chromiumMajor = parsed;
					}
				} catch (NumberFormatException e) {
					// Use the privacy-safe fallback.
				}
			}
		}
		return formatUserAgent(chromiumMajor);
	}

	private static String formatUserAgent(int chromiumMajor) {
		return String.format(Locale.US, USER_AGENT_FORMAT, chromiumMajor);
	}

	public String getUserAgent() {
		return userAgent;
	}
}
