package com.mishiranu.dashchan.ui;

/** Announcement identity is independent of the release version and the cleanup configuration. */
final class PageCleanupNoticePolicy {
	static final int LIMIT = 3;
	private PageCleanupNoticePolicy() {}
	static boolean eligible(int shown, boolean acknowledged, boolean enabled, boolean claimed) {
		return !acknowledged && !enabled && !claimed && Math.max(0, shown) < LIMIT;
	}
	static int nextCount(int shown) { return Math.min(LIMIT, Math.max(0, Math.min(LIMIT, shown)) + 1); }
}
