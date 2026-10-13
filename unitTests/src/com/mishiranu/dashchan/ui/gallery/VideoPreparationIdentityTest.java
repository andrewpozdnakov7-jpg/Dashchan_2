package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import org.junit.Test;

public class VideoPreparationIdentityTest {
	private static VideoPreparationIdentity identity(String uri, String path, long length, long modified,
			long position, boolean seek, boolean hardware) {
		return new VideoPreparationIdentity(uri, path, length, modified, position, seek, hardware);
	}

	@Test public void onlyTheExactFileAndPlaybackPolicyCanBeReused() {
		VideoPreparationIdentity expected = identity("video-a", "cache-a", 100, 10, 0, true, true);
		assertTrue(expected.matches(identity("video-a", "cache-a", 100, 10, 0, true, true)));
		assertFalse(expected.matches(identity("video-b", "cache-a", 100, 10, 0, true, true)));
		assertFalse(expected.matches(identity("video-a", "cache-b", 100, 10, 0, true, true)));
		assertFalse(expected.matches(identity("video-a", "cache-a", 101, 10, 0, true, true)));
		assertFalse(expected.matches(identity("video-a", "cache-a", 100, 11, 0, true, true)));
		assertFalse(expected.matches(identity("video-a", "cache-a", 100, 10, 50, true, true)));
		assertFalse(expected.matches(identity("video-a", "cache-a", 100, 10, 0, false, true)));
		assertFalse(expected.matches(identity("video-a", "cache-a", 100, 10, 0, true, false)));
		assertFalse(expected.matches(null));
	}

	@Test public void startPositionMustNotTreatEofAsAResumeFrame() {
		assertEquals(150, VideoPreparationIdentity.startPosition(150, 1000));
		assertEquals(0, VideoPreparationIdentity.startPosition(1000, 1000));
		assertEquals(0, VideoPreparationIdentity.startPosition(1001, 1000));
		assertEquals(0, VideoPreparationIdentity.startPosition(-1, 1000));
		assertEquals(0, VideoPreparationIdentity.startPosition(150, 0));
	}
}
