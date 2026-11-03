package com.mishiranu.dashchan.content;

/** The same observed thread can be watched both as a favorite and because it contains our posts. */
public enum ReplyNotificationTargets {
	NONE(false, false), FAVORITE(true, false), TRACKED(false, true), BOTH(true, true);

	private final boolean favorite, tracked;

	ReplyNotificationTargets(boolean favorite, boolean tracked) {
		this.favorite = favorite;
		this.tracked = tracked;
	}

	public static ReplyNotificationTargets of(boolean favorite, boolean tracked) {
		return favorite ? (tracked ? BOTH : FAVORITE) : (tracked ? TRACKED : NONE);
	}

	static ReplyNotificationTargets restoreScope(String stored) {
		if (FAVORITE.name().equals(stored)) return FAVORITE;
		if (TRACKED.name().equals(stored)) return TRACKED;
		return BOTH;
	}

	boolean allows(boolean history, boolean quiet, boolean favoriteAlerts, boolean trackedAlerts) {
		return history && !quiet && (favorite && favoriteAlerts || tracked && trackedAlerts);
	}
}
