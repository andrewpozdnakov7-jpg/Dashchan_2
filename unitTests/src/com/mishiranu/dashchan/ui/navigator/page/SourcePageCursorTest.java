package com.mishiranu.dashchan.ui.navigator.page;

import static org.junit.Assert.*;
import org.junit.Test;

public class SourcePageCursorTest {
	@Test public void sourcesAdvanceIndependently() {
		SourcePageCursor first = new SourcePageCursor();
		SourcePageCursor second = new SourcePageCursor();
		first.success(0, false);
		first.success(1, false);
		assertEquals(2, first.nextPage());
		assertEquals(0, second.nextPage());
		second.success(0, false);
		assertEquals(1, second.nextPage());
	}

	@Test public void failedOrCancelledRequestsDoNotSkipPages() {
		SourcePageCursor cursor = new SourcePageCursor();
		cursor.success(0, false);
		// Neither failure nor cancellation calls success().
		assertEquals(1, cursor.nextPage());
		assertTrue(cursor.canLoad(5));
		cursor.success(1, false);
		assertEquals(2, cursor.nextPage());
	}

	@Test public void emptyResponseEndsOnlyThatSource() {
		SourcePageCursor cursor = new SourcePageCursor();
		cursor.success(0, false);
		cursor.success(1, true);
		assertFalse(cursor.canLoad(Integer.MAX_VALUE));
		assertTrue(new SourcePageCursor().canLoad(Integer.MAX_VALUE));
	}

	@Test public void knownPageLimitIsRespected() {
		SourcePageCursor cursor = new SourcePageCursor();
		assertTrue(cursor.canLoad(1));
		cursor.success(0, false);
		assertFalse(cursor.canLoad(1));
		assertTrue(cursor.canLoad(2));
	}

	@Test public void duplicateOrOutOfOrderResultDoesNotAdvance() {
		SourcePageCursor cursor = new SourcePageCursor();
		cursor.success(0, false);
		cursor.success(0, false);
		cursor.success(5, false);
		assertEquals(1, cursor.nextPage());
	}
}
