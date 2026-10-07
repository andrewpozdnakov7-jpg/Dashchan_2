package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import org.junit.Test;

public class LastVideoPositionTest {
	private static final String FIRST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
	private static final String SECOND = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

	@Test public void bookmarkRoundTripsAndResumesOnlyMatchingVideo() {
		LastVideoPosition bookmark = LastVideoPosition.decode(new LastVideoPosition(FIRST, 12345L).encode());
		assertNotNull(bookmark);
		assertEquals(12345L, bookmark.resumePosition(FIRST, 60000L));
		assertEquals(0L, bookmark.resumePosition(SECOND, 60000L));
	}

	@Test public void finishedVideoAndInvalidDurationStartAtZero() {
		LastVideoPosition bookmark = new LastVideoPosition(FIRST, 30000L);
		assertEquals(0L, bookmark.resumePosition(FIRST, 30000L));
		assertEquals(0L, bookmark.resumePosition(FIRST, 20000L));
		assertEquals(0L, bookmark.resumePosition(FIRST, 0L));
		assertEquals(0L, bookmark.resumePosition(FIRST, -1L));
	}

	@Test public void completionResetsBookmarkRatherThanSavingEndOfFile() {
		assertEquals(0L, LastVideoPosition.capture(FIRST, 60000L, 60000L, false).position);
		assertEquals(0L, LastVideoPosition.capture(FIRST, 59000L, 60000L, true).position);
		assertEquals(0L, LastVideoPosition.capture(FIRST, Long.MAX_VALUE, 60000L, false).position);
		assertEquals(0L, LastVideoPosition.capture(FIRST, 1000L, 0L, false).position);
	}

	@Test public void manualReplayAndSeekCanReplaceCompletedPosition() {
		LastVideoPosition completed = LastVideoPosition.capture(FIRST, 60000L, 60000L, true);
		assertEquals(0L, completed.position);
		assertEquals(500L, LastVideoPosition.capture(FIRST, 500L, 60000L, false).position);
		assertEquals(20000L, LastVideoPosition.capture(FIRST, 20000L, 60000L, false).position);
		assertEquals(0L, LastVideoPosition.capture(FIRST, -1L, 60000L, false).position);
	}

	@Test public void replacingBookmarkDoesNotCreateHistory() {
		String persisted = new LastVideoPosition(FIRST, 12345L).encode();
		persisted = new LastVideoPosition(SECOND, 23456L).encode();
		LastVideoPosition bookmark = LastVideoPosition.decode(persisted);
		assertNotNull(bookmark);
		assertEquals(0L, bookmark.resumePosition(FIRST, 60000L));
		assertEquals(23456L, bookmark.resumePosition(SECOND, 60000L));
	}

	@Test public void malformedOrOverflowingPreferencesAreIgnored() {
		for (String value : new String[] {null, "", "123:45", FIRST + ":", FIRST + ":-1",
				FIRST + ":abc", FIRST + ":9223372036854775808", "g" + FIRST.substring(1) + ":10",
				FIRST + "/10", FIRST + ":10:20"}) {
			assertNull(value, LastVideoPosition.decode(value));
		}
	}
}
