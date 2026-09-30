package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import org.junit.Test;

public class PlaybackCompletionTest {
	@Test public void deliversEndOfCurrentPlayback() {
		assertTrue(new PlaybackCompletion(2, 3).isCurrent(2, 3, false));
	}

	@Test public void rejectsEndQueuedBeforeReplayRequest() {
		assertFalse(new PlaybackCompletion(2, 3).isCurrent(2, 4, false));
	}

	@Test public void rejectsEndEmittedDuringReplacementSeek() {
		PlaybackCompletion end = new PlaybackCompletion(2, 4);
		assertFalse(end.isCurrent(2, 4, true));
		assertFalse(end.isCurrent(3, 4, false));
	}

	@Test public void acceptsRealEndAfterReplayAndLoop() {
		assertTrue(new PlaybackCompletion(3, 4).isCurrent(3, 4, false));
		assertFalse(new PlaybackCompletion(3, 4).isCurrent(4, 5, false));
		assertTrue(new PlaybackCompletion(4, 5).isCurrent(4, 5, false));
	}
}
