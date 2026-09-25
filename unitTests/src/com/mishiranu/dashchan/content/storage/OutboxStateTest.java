package com.mishiranu.dashchan.content.storage;

import static org.junit.Assert.*;
import org.junit.Test;

public class OutboxStateTest {
	@Test public void noRecoveredEntryAutomaticallyResumesSending() {
		for (OutboxState state : OutboxState.values()) assertFalse(state.afterProcessDeath().isActive());
	}

	@Test public void inFlightRequestBecomesUnknownNotFailed() {
		assertEquals(OutboxState.UNKNOWN_RESULT, OutboxState.SENDING.afterProcessDeath());
		assertEquals(OutboxState.UNKNOWN_RESULT, OutboxState.SENDING.afterCancellation());
	}

	@Test public void unsentAndRateLimitedMessagesRequireManualRecovery() {
		for (OutboxState state : new OutboxState[] {OutboxState.PREPARING, OutboxState.READY, OutboxState.WAITING}) {
			assertEquals(OutboxState.INTERRUPTED, state.afterProcessDeath());
			assertEquals(OutboxState.INTERRUPTED, state.afterCancellation());
		}
	}

	@Test public void acceptedAndActionRequiredStatesSurviveRestart() {
		for (OutboxState state : OutboxState.values()) {
			if (!state.isActive()) assertEquals(state, state.afterProcessDeath());
		}
		assertEquals(OutboxState.SENT, OutboxState.SENT.afterCancellation());
	}

	@Test public void recoveryIsIdempotent() {
		for (OutboxState state : OutboxState.values()) {
			assertEquals(state.afterProcessDeath(), state.afterProcessDeath().afterProcessDeath());
			assertEquals(state.afterCancellation(), state.afterCancellation().afterCancellation());
		}
		assertEquals(OutboxState.UNKNOWN_RESULT, OutboxState.UNKNOWN_RESULT.afterCancellation());
	}
}
