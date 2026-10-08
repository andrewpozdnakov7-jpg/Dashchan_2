package com.mishiranu.dashchan.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationRequestQueueTest {
	@Test public void idleRequestDoesNotRunOrQueue() {
		NavigationRequestQueue queue = new NavigationRequestQueue();
		assertFalse(queue.defer(() -> fail("Unexpected execution"), false));
		assertFalse(queue.hasRequests());
	}

	@Test public void waitsForExactTransactionAndPreservesOrder() {
		NavigationRequestQueue queue = new NavigationRequestQueue();
		List<Integer> values = new ArrayList<>();
		int ticket = queue.begin();
		assertTrue(queue.defer(() -> values.add(1), false));
		assertTrue(queue.defer(() -> values.add(2), false));
		queue.drainOne(false);
		assertTrue(values.isEmpty());
		assertFalse(queue.complete(ticket + 1));
		assertTrue(queue.isPending());
		assertTrue(queue.complete(ticket));
		assertTrue(queue.defer(() -> values.add(3), false));
		queue.drainOne(false);
		queue.drainOne(false);
		queue.drainOne(false);
		assertEquals(Arrays.asList(1, 2, 3), values);
	}

	@Test public void nestedHelpersDoNotRequeueTheRunningRequest() {
		NavigationRequestQueue queue = new NavigationRequestQueue();
		assertTrue(queue.defer(() -> assertFalse(queue.defer(() -> fail(), false)), true));
		assertTrue(queue.defer(() -> {}, true));
		queue.drainOne(false);
		queue.drainOne(false);
		assertFalse(queue.hasRequests());
	}

	@Test public void nextNavigationStopsDrainUntilItsCommit() {
		NavigationRequestQueue queue = new NavigationRequestQueue();
		int[] next = {0};
		List<Integer> values = new ArrayList<>();
		int ticket = queue.begin();
		queue.defer(() -> next[0] = queue.begin(), false);
		queue.defer(() -> values.add(1), false);
		queue.complete(ticket);
		queue.drainOne(false);
		queue.drainOne(false);
		assertTrue(values.isEmpty());
		assertFalse(queue.complete(ticket));
		assertTrue(queue.complete(next[0]));
		queue.drainOne(false);
		assertEquals(Arrays.asList(1), values);
	}

	@Test public void savedStateBlocksDrainUntilResume() {
		NavigationRequestQueue queue = new NavigationRequestQueue();
		List<Integer> values = new ArrayList<>();
		assertTrue(queue.defer(() -> values.add(1), true));
		queue.drainOne(true);
		assertTrue(values.isEmpty());
		queue.drainOne(false);
		assertEquals(Arrays.asList(1), values);
	}

	@Test public void destroyDiscardsOldActivityCallbacks() {
		NavigationRequestQueue queue = new NavigationRequestQueue();
		int ticket = queue.begin();
		queue.defer(() -> fail(), false);
		queue.close();
		assertFalse(queue.complete(ticket));
		assertTrue(queue.defer(() -> fail(), false));
		queue.drainOne(false);
		assertFalse(queue.hasRequests());
	}

	@Test public void exceptionDoesNotLeaveDrainFlagSet() {
		NavigationRequestQueue queue = new NavigationRequestQueue();
		queue.defer(() -> { throw new IllegalArgumentException(); }, true);
		try { queue.drainOne(false); fail(); } catch (IllegalArgumentException expected) {}
		assertFalse(queue.defer(() -> {}, false));
	}

	@Test(expected = IllegalStateException.class) public void rejectsOverlappingTransactions() {
		NavigationRequestQueue queue = new NavigationRequestQueue();
		queue.begin();
		queue.begin();
	}
	@Test public void staleDuplicateCompletionCannotReleaseNextTransaction() {
		NavigationRequestQueue queue = new NavigationRequestQueue();
		int first = queue.begin();
		assertTrue(queue.complete(first));
		int second = queue.begin();
		assertFalse(queue.complete(first));
		assertTrue(queue.isPending());
		assertTrue(queue.complete(second));
		assertFalse(queue.complete(second));
	}

	@Test public void savedStateRequeuePreservesRapidRequestOrder() {
		NavigationRequestQueue queue = new NavigationRequestQueue();
		List<Integer> values = new ArrayList<>();
		queue.defer(() -> values.add(1), true);
		queue.defer(() -> values.add(2), true);
		queue.drainOne(true); assertTrue(values.isEmpty());
		queue.drainOne(false);
		queue.defer(() -> values.add(3), false);
		queue.drainOne(false); queue.drainOne(false);
		assertEquals(Arrays.asList(1, 2, 3), values);
	}
}
