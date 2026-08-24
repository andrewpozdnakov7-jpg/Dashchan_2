package com.mishiranu.dashchan.content.push;

import android.content.Context;

public final class ReplyPushBridge {
	private ReplyPushBridge() {}

	public static boolean isSupported() {
		return false;
	}

	public static boolean isConfigured() {
		return false;
	}

	public static void setEnabled(Context context, boolean enabled, String installationId) {}

	public static void registerWatch(Context context, String installationId, String chanName,
			String boardName, String threadNumber, String postNumber) {}
}
