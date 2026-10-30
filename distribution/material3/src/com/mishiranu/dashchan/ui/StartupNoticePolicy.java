package com.mishiranu.dashchan.ui;

/** Process-scoped launch reservation; disk acknowledgement is independent of version and UI settings. */
final class StartupNoticePolicy {
	static final int LIMIT = 3;
	private boolean shown;

	boolean claim(int acknowledgements) {
		if (shown || acknowledgements >= LIMIT) return false;
		shown = true;
		return true;
	}

	void restoreVisible() { shown = true; }

	static int nextCount(int current) { return Math.min(LIMIT, Math.max(0, current) + 1); }
}
