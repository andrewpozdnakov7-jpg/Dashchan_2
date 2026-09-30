package com.mishiranu.dashchan.ui.navigator.page;

/** Main-thread pagination cursor owned by one feed source, advanced only by accepted results. */
final class SourcePageCursor {
	private int nextPage;
	private boolean exhausted;

	int nextPage() { return nextPage; }
	boolean canLoad(int pageCount) { return !exhausted && nextPage < Math.max(1, pageCount); }

	void success(int page, boolean empty) {
		if (page != nextPage) return;
		if (empty) exhausted = true;
		else nextPage++;
	}
}
