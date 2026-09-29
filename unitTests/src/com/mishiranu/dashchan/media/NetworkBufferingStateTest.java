package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import org.junit.Test;

public class NetworkBufferingStateTest {
	@Test public void bufferingPausesAndThenResumesOnlyRequestedPlayback() {
		NetworkBufferingState state = new NetworkBufferingState();
		assertTrue(state.shouldPlay(true));
		assertTrue(state.apply(1, true));
		assertFalse(state.shouldPlay(true));
		assertTrue(state.shouldShowProgress(true));
		assertTrue(state.apply(2, false));
		assertTrue(state.shouldPlay(true));
		assertFalse(state.shouldShowProgress(true));
	}

	@Test public void manualPauseDuringBufferingNeverAutoResumes() {
		NetworkBufferingState state = new NetworkBufferingState();
		state.apply(1, true);
		assertFalse(state.shouldPlay(false));
		assertFalse(state.shouldShowProgress(false));
		state.apply(2, false);
		assertFalse(state.shouldPlay(false));
	}

	@Test public void manualPlayDuringBufferingWaitsForReady() {
		NetworkBufferingState state = new NetworkBufferingState();
		state.apply(1, true);
		assertFalse(state.shouldPlay(true));
		state.apply(2, false);
		assertTrue(state.shouldPlay(true));
	}

	@Test public void oldReadyCannotResumeNewBufferingAfterSeek() {
		NetworkBufferingState state = new NetworkBufferingState();
		state.apply(3, true);
		assertFalse(state.apply(2, false));
		assertFalse(state.apply(3, false));
		assertFalse(state.shouldPlay(true));
		state.apply(4, false);
		assertTrue(state.shouldPlay(true));
	}

	@Test public void oldStartCannotRestoreSpinnerAfterReady() {
		NetworkBufferingState state = new NetworkBufferingState();
		state.apply(2, false);
		assertFalse(state.apply(1, true));
		assertTrue(state.shouldPlay(true));
		assertFalse(state.shouldShowProgress(true));
	}
}
