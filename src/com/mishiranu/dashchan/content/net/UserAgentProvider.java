package com.mishiranu.dashchan.content.net;

import android.app.Application;
import android.webkit.WebSettings;

import chan.util.StringUtils;

public class UserAgentProvider {
	private static final UserAgentProvider INSTANCE = new UserAgentProvider();

	public static void initialize(Application application) {
		try {
			String userAgent = WebSettings.getDefaultUserAgent(application);
			if (!StringUtils.isEmpty(userAgent)) {
				INSTANCE.userAgent = userAgent;
			}
		} catch (RuntimeException e) {
			e.printStackTrace();
		}
	}

	public static UserAgentProvider getInstance() {
		return INSTANCE;
	}

	private String userAgent = "Mozilla/5.0";

	private UserAgentProvider() {}

	public String getUserAgent() {
		return userAgent;
	}
}
