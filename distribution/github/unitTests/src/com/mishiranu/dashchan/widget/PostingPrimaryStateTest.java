package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import org.junit.Test;
import com.mishiranu.dashchan.widget.PostingPrimaryState.Mode;

public class PostingPrimaryStateTest {
	@Test public void realLoadingAlwaysWinsAndBypassNeedsNoSuccessAnimation() {
		assertEquals(Mode.LOADING, PostingPrimaryState.resolve(true, true));
		assertEquals(Mode.SOLVE, PostingPrimaryState.resolve(false, false));
		assertEquals(Mode.SEND, PostingPrimaryState.resolve(false, true));
		PostingPrimaryState state = new PostingPrimaryState();
		assertTrue(state.bind(Mode.SOLVE, false, false).initial);
		assertFalse(state.bind(Mode.SEND, false, false).success); // Not required is not a solved answer.
	}
	@Test public void savedAnswerCelebratesOnlyANewEdgeNeverRestoreOrFirstBind() {
		PostingPrimaryState state = new PostingPrimaryState();
		assertFalse(state.bind(Mode.SEND, true, false).success);
		state.bind(Mode.LOADING, false, false);
		assertTrue(state.bind(Mode.SEND, true, false).success);
		PostingPrimaryState.Change repeated = state.bind(Mode.SEND, true, false);
		assertFalse(repeated.changed); assertFalse(repeated.success);
		state.bind(Mode.SOLVE, false, false);
		assertFalse(state.bind(Mode.SEND, true, true).success);
		assertFalse(state.bind(Mode.SEND, true, false).success);
	}
	@Test public void preloadingWithoutAnExplicitClickNeverOpensAChallenge() {
		PostingPrimaryState state = new PostingPrimaryState();
		state.bind(Mode.LOADING, false, false);
		assertFalse(state.requestDialog());
		state.bind(Mode.SOLVE, false, false);
		assertFalse(state.consumeDialogRequest(true));
		assertTrue(state.requestDialog()); assertFalse(state.requestDialog());
		assertTrue(state.consumeDialogRequest(true)); assertFalse(state.consumeDialogRequest(true));
	}
	@Test public void explicitRequestWaitsForTheImageAndIsConsumedOnce() {
		PostingPrimaryState state = new PostingPrimaryState();
		state.bind(Mode.SOLVE, false, false); assertTrue(state.requestDialog());
		state.bind(Mode.LOADING, false, false);
		assertFalse(state.consumeDialogRequest(true));
		assertFalse(state.consumeDialogRequest(false));
		state.bind(Mode.SOLVE, false, false);
		assertTrue(state.consumeDialogRequest(true));
		assertFalse(state.consumeDialogRequest(true));
	}
	@Test public void pauseErrorAndCloseInvalidatePendingDialogRequests() {
		PostingPrimaryState state = new PostingPrimaryState();
		state.bind(Mode.SOLVE, false, false); assertTrue(state.requestDialog());
		state.cancelRequest(); assertFalse(state.consumeDialogRequest(true));
		assertTrue(state.requestDialog()); state.close();
		assertFalse(state.consumeDialogRequest(true)); assertFalse(state.requestDialog());
		assertFalse(state.bind(Mode.SEND, true, false).success);
	}
	@Test public void completedOrInvalidatedAnswersDoNotRetainDialogOwnership() {
		PostingPrimaryState state = new PostingPrimaryState();
		state.bind(Mode.SOLVE, false, false); assertTrue(state.requestDialog());
		state.bind(Mode.SEND, true, false);
		assertFalse(state.consumeDialogRequest(true)); assertFalse(state.requestDialog());
		state.bind(Mode.SOLVE, false, false);
		assertTrue(state.requestDialog());
	}
}
