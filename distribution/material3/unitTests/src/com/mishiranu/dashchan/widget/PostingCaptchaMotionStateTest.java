package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import org.junit.Test;
import com.mishiranu.dashchan.widget.PostingCaptchaMotionState.State;

public class PostingCaptchaMotionStateTest {
	@Test public void rapidChangesInvalidateOldAnimationWithoutReplayingTheSameState() {
		PostingCaptchaMotionState state = new PostingCaptchaMotionState();
		assertFalse(state.bind(State.LOADING, false, true).animate);
		PostingCaptchaMotionState.Change ready = state.bind(State.READY, false, true);
		assertTrue(ready.animate);
		PostingCaptchaMotionState.Change done = state.bind(State.DONE, false, true);
		assertTrue(done.success); assertFalse(state.owns(ready.revision)); assertTrue(state.owns(done.revision));
		assertFalse(state.bind(State.DONE, false, true).animate);
		PostingCaptchaMotionState.Change retry = state.bind(State.ERROR, false, true);
		assertTrue(retry.animate); assertFalse(retry.success); assertFalse(state.owns(done.revision));
		state.bind(State.LOADING, false, true); assertFalse(state.owns(retry.revision));
	}
	@Test public void restoreDisabledAnimationsAndCloseCannotCelebrateOrPublishOldState() {
		PostingCaptchaMotionState state = new PostingCaptchaMotionState();
		state.bind(State.LOADING, false, true);
		PostingCaptchaMotionState.Change done = state.bind(State.DONE, true, true);
		assertFalse(done.animate); assertFalse(done.success);
		assertFalse(state.bind(State.READY, false, false).animate);
		state.close(); assertFalse(state.owns(done.revision)); assertFalse(state.bind(State.DONE, false, true).animate);
	}
	@Test public void onlyAnAnswerShowsDoneAndLoadingOrErrorInvalidatesItImmediately() {
		assertEquals(State.LEGACY, PostingCaptchaMotionState.resolve(false, false, false, false));
		assertEquals(State.READY, PostingCaptchaMotionState.resolve(false, false, false, true));
		assertEquals(State.DONE, PostingCaptchaMotionState.resolve(false, false, true, false));
		assertEquals(State.LOADING, PostingCaptchaMotionState.resolve(true, false, true, true));
		assertEquals(State.ERROR, PostingCaptchaMotionState.resolve(false, true, true, true));
	}
}
