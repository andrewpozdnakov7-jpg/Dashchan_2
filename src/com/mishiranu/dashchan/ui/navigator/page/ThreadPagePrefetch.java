package com.mishiranu.dashchan.ui.navigator.page;

/** Main-thread policy for one speculative page, with a bounded hidden/duplicate-only continuation. */
final class ThreadPagePrefetch {
	static final int NONE = -1;
	static final int MAX_BATCH_PAGES = 3;
	private int windowStart;
	private int windowEnd;
	private int attemptedPage = NONE;
	private int loadingPage = NONE;
	private int batchPages;
	private int batchStartCount;
	private boolean endReached;

	void resetWindow(int start, int end) {
		windowEnd = Math.max(0, end);
		windowStart = Math.max(0, Math.min(start, windowEnd));
		attemptedPage = NONE;
		endReached = false;
	}

	boolean canStart(int page, int lastVisible) {
		return !isLoading() && !endReached && page >= 0 && page != attemptedPage
				&& windowEnd > windowStart
				&& lastVisible >= windowStart + (windowEnd - windowStart - 1) / 2;
	}

	boolean isLoading() { return loadingPage != NONE; }
	boolean isLoading(int page) { return isLoading() && loadingPage == page; }

	void begin(int page, int visibleCount) {
		loadingPage = attemptedPage = page;
		batchStartCount = visibleCount;
		batchPages = 1;
	}

	boolean continueHiddenPage(int nextPage, int visibleCount) {
		if (visibleCount > batchStartCount || batchPages >= MAX_BATCH_PAGES) return false;
		loadingPage = attemptedPage = nextPage;
		batchPages++;
		return true;
	}

	void finish(int nextPage, int visibleCount, boolean endReached) {
		if (visibleCount > batchStartCount) {
			resetWindow(batchStartCount, visibleCount);
		} else {
			// Do not start another invisible batch on the next scroll/layout event.
			attemptedPage = nextPage;
		}
		loadingPage = NONE;
		this.endReached = endReached;
	}

	void fail() {
		loadingPage = NONE;
		// Keep attemptedPage: errors need a deliberate manual retry, not a scroll-triggered loop.
	}

	void cancel() {
		if (isLoading()) attemptedPage = NONE;
		loadingPage = NONE;
	}
}
