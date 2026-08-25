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

	public static boolean hasConsent(Context context) {
		return false;
	}

	public static String getInstallationId(Context context) {
		return null;
	}

	public static void setInstallationId(Context context, String installationId) {
		if (installationId == null) {
			com.mishiranu.dashchan.content.Preferences.takeLegacyReplyPushInstallationId();
		}
	}

	public static void setEnabled(Context context, boolean enabled, String installationId) {}

	public static void registerWatch(Context context, String installationId, String chanName,
			String boardName, String threadNumber, String postNumber) {}

	public static boolean resetIdentity(Context context, String installationId) {
		return false;
	}

	public static boolean isIdentityResetPending(Context context) {
		return false;
	}

	public static boolean didIdentityResetFail(Context context) {
		return false;
	}

	public static long getIdentityResetCooldownRemaining(Context context) {
		return 0L;
	}
}
