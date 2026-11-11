package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import org.junit.Test;

public class PlaybackLockStateTest {
	@Test public void repeatedLockedResumeKeepsTheOriginalPlayIntentUntilAuthentication() {
		PlaybackLockState state = new PlaybackLockState();
		assertTrue(state.request(true, false));
		boolean resume = state.beforePause(true);
		for (int i = 0; i < 2; i++) {
			assertFalse(state.request(resume, true));
			resume = state.beforePause(false);
			assertTrue(resume);
		}
		assertTrue(state.request(resume, false));
	}
	@Test public void userPauseOrDiscardedMediaDoesNotReviveAnOldResumeRequest() {
		PlaybackLockState state = new PlaybackLockState();
		assertFalse(state.request(true, true));
		assertFalse(state.request(false, true));
		assertFalse(state.beforePause(false));
		assertFalse(state.request(false, false));
		assertFalse(state.request(true, true)); state.reset();
		assertFalse(state.beforePause(false));
	}
}
