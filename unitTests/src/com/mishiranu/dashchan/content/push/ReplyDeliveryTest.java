package com.mishiranu.dashchan.content.push;

import static org.junit.Assert.*;
import org.junit.Test;

public class ReplyDeliveryTest {
	private static final class State implements ReplyDelivery.Steps {
		boolean pending = true, persistedPending, handled, quiet, failNotification;
		int writes, failWrite, notifications;
		@Override public boolean persist() {
			if (++writes == failWrite) return false;
			persistedPending = pending;
			return true;
		}
		@Override public boolean deliverPending() {
			if (failNotification) return false;
			if (pending && !quiet) notifications++;
			return true;
		}
		@Override public void complete() { pending = false; }
		@Override public void markHandled() { handled = true; }
	}

	@Test public void retryAfterInitialSaveFailureStillNotifies() {
		State state = new State();
		state.failWrite = 1;
		assertFalse(ReplyDelivery.finish(state));
		assertTrue(state.pending);
		assertFalse(state.handled);
		assertEquals(0, state.notifications);
		assertTrue(ReplyDelivery.finish(state));
		assertEquals(1, state.notifications);
		assertTrue(state.handled);
		assertFalse(state.persistedPending);
	}

	@Test public void completionSaveFailureRetriesWithoutSecondNotificationInProcess() {
		State state = new State();
		state.failWrite = 2;
		assertFalse(ReplyDelivery.finish(state));
		assertFalse(state.handled);
		assertTrue(state.persistedPending);
		assertTrue(ReplyDelivery.finish(state));
		assertEquals(1, state.notifications);
		assertFalse(state.persistedPending);
	}

	@Test public void failedNotificationRemainsPending() {
		State state = new State();
		state.failNotification = true;
		assertFalse(ReplyDelivery.finish(state));
		assertTrue(state.pending);
		assertTrue(state.persistedPending);
		assertFalse(state.handled);
		state.failNotification = false;
		assertTrue(ReplyDelivery.finish(state));
		assertEquals(1, state.notifications);
	}

	@Test public void quietHoursFinishWithoutDelayedAlert() {
		State state = new State();
		state.quiet = true;
		assertTrue(ReplyDelivery.finish(state));
		state.quiet = false;
		assertTrue(ReplyDelivery.finish(state));
		assertEquals(0, state.notifications);
	}

	@Test public void existingOrClearedReplyDoesNotNotify() {
		State state = new State();
		state.pending = false;
		assertTrue(ReplyDelivery.finish(state));
		assertEquals(0, state.notifications);
	}
}
