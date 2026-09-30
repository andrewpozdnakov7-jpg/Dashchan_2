package com.mishiranu.dashchan.ui.navigator.page;

import static org.junit.Assert.*;
import org.junit.Test;

public class ThreadPagePrefetchTest {
	@Test public void startsAtMidpointNotOnTheFirstRows() {
		ThreadPagePrefetch state = new ThreadPagePrefetch();
		state.resetWindow(0, 20);
		assertFalse(state.canStart(1, 8));
		assertTrue(state.canStart(1, 9));
		assertFalse(state.canStart(1, -1));
	}

	@Test public void oneRequestAtATimeAndThenMidpointOfNewPage() {
		ThreadPagePrefetch state = new ThreadPagePrefetch();
		state.resetWindow(0, 20);
		state.begin(1, 20);
		assertTrue(state.isLoading(1));
		assertFalse(state.isLoading(2));
		assertFalse(state.canStart(1, 19));
		assertFalse(state.canStart(2, 39));
		assertFalse(state.continueHiddenPage(2, 40));
		state.finish(2, 40, false);
		assertFalse(state.isLoading());
		assertFalse(state.canStart(2, 28));
		assertTrue(state.canStart(2, 29));
	}

	@Test public void invisiblePagesAreBoundedAndCannotStartAnotherBatchOnScroll() {
		ThreadPagePrefetch state = new ThreadPagePrefetch();
		state.resetWindow(0, 20);
		state.begin(1, 20);
		assertTrue(state.continueHiddenPage(2, 20));
		assertTrue(state.continueHiddenPage(3, 20));
		assertFalse(state.continueHiddenPage(4, 20));
		state.finish(4, 20, false);
		assertFalse(state.canStart(4, 19));
	}

	@Test public void visibleThreadsAfterHiddenPageEstablishNewWindow() {
		ThreadPagePrefetch state = new ThreadPagePrefetch();
		state.resetWindow(0, 20);
		state.begin(1, 20);
		assertTrue(state.continueHiddenPage(2, 20));
		assertFalse(state.continueHiddenPage(3, 26));
		state.finish(3, 26, false);
		assertFalse(state.canStart(3, 21));
		assertTrue(state.canStart(3, 22));
	}

	@Test public void failureDoesNotRetryOnEveryScrollIncludingAfterHiddenPage() {
		ThreadPagePrefetch state = new ThreadPagePrefetch();
		state.resetWindow(0, 20);
		state.begin(1, 20);
		assertTrue(state.continueHiddenPage(2, 20));
		state.fail();
		assertFalse(state.isLoading());
		assertFalse(state.canStart(2, 19));
		// Returning from another screen alone must not reset an already failed attempt.
		state.cancel();
		assertFalse(state.canStart(2, 19));
		state.resetWindow(0, 20);
		assertTrue(state.canStart(2, 19));
	}

	@Test public void cancellationAllowsRetryAfterReturnButNotAStaleResult() {
		ThreadPagePrefetch state = new ThreadPagePrefetch();
		state.resetWindow(0, 20);
		state.begin(1, 20);
		state.cancel();
		assertFalse(state.isLoading(1));
		assertTrue(state.canStart(1, 19));
	}

	@Test public void endOfBoardStaysStoppedUntilRefresh() {
		ThreadPagePrefetch state = new ThreadPagePrefetch();
		state.resetWindow(0, 20);
		state.begin(1, 20);
		state.finish(1, 20, true);
		assertFalse(state.canStart(1, 19));
		assertFalse(state.canStart(2, 19));
		state.cancel();
		assertFalse(state.canStart(2, 19));
		state.resetWindow(0, 20);
		assertTrue(state.canStart(1, 19));
	}

	@Test public void emptyWindowNeverStartsAndSingleRowCanStart() {
		ThreadPagePrefetch state = new ThreadPagePrefetch();
		state.resetWindow(0, 0);
		assertFalse(state.canStart(1, 0));
		state.resetWindow(20, 20);
		assertFalse(state.canStart(1, 19));
		state.resetWindow(20, 21);
		assertTrue(state.canStart(1, 20));
		assertFalse(state.canStart(-1, 20));
	}
}
