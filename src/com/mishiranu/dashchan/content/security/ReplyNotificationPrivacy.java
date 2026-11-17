package com.mishiranu.dashchan.content.security;

/** Shared notification-content policy; historical class name retained for source compatibility. Never grants authentication. */
public final class ReplyNotificationPrivacy {
	private ReplyNotificationPrivacy() {}
	public static boolean hide(boolean protectedApp, boolean hideInBackground, boolean foregroundUnlocked,
			boolean resetPending) {
		return resetPending || protectedApp && hideInBackground && !foregroundUnlocked;
	}
}
