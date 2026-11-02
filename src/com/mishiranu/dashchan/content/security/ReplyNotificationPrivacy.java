package com.mishiranu.dashchan.content.security;

/** Reply-content policy only; never grants an authentication session or controls other notifications. */
public final class ReplyNotificationPrivacy {
	private ReplyNotificationPrivacy() {}
	public static boolean hide(boolean protectedApp, boolean hideInBackground, boolean foregroundUnlocked,
			boolean resetPending) {
		return resetPending || protectedApp && hideInBackground && !foregroundUnlocked;
	}
}
