package com.mishiranu.dashchan.content.async;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Map;
import org.junit.Test;

public class ProgressiveLoadStateTest {
	@Test public void fastSourceAppearsBeforeSlowAndSnapshotDoesNotChange() {
		ProgressiveLoadState<String> state = new ProgressiveLoadState<>();
		int generation = state.begin(Arrays.asList("fast", "slow"), value -> false);
		assertTrue(state.complete(generation, "fast", "posts"));
		Map<String, String> first = state.snapshot();
		assertEquals("posts", first.get("fast"));
		assertEquals(1, state.pendingCount());
		assertTrue(state.complete(generation, "slow", "error"));
		assertEquals(0, state.pendingCount());
		assertEquals(1, first.size());
		assertEquals(2, state.snapshot().size());
	}

	@Test public void oldAndDuplicateCallbacksCannotCompleteCurrentRequest() {
		ProgressiveLoadState<String> state = new ProgressiveLoadState<>();
		int old = state.begin(Arrays.asList("board"), value -> false);
		int current = state.begin(Arrays.asList("board"), value -> false);
		assertFalse(state.complete(old, "board", "stale"));
		assertEquals(1, state.pendingCount());
		assertTrue(state.complete(current, "board", "new"));
		assertFalse(state.complete(current, "board", "duplicate"));
		assertEquals("new", state.snapshot().get("board"));
	}

	@Test public void retryKeepsSuccessAndOnlyRequestsFailure() {
		ProgressiveLoadState<String> state = new ProgressiveLoadState<>();
		int generation = state.begin(Arrays.asList("a", "b"), value -> false);
		state.complete(generation, "a", "ok");
		state.complete(generation, "b", "error");
		int retry = state.begin(Arrays.asList("a", "b"), "ok"::equals);
		assertFalse(state.isPending("a"));
		assertTrue(state.isPending("b"));
		assertEquals("ok", state.snapshot().get("a"));
		assertEquals(2, state.totalCount());
		assertTrue(state.complete(retry, "b", "ok"));
	}

	@Test public void removedSourcesAndDuplicateKeysAreHandled() {
		ProgressiveLoadState<String> state = new ProgressiveLoadState<>();
		int first = state.begin(Arrays.asList("a", "b", "a"), value -> false);
		assertEquals(2, state.totalCount());
		state.complete(first, "a", "ok");
		state.complete(first, "b", "ok");
		state.begin(Arrays.asList("b", "c"), value -> true);
		assertFalse(state.snapshot().containsKey("a"));
		assertTrue(state.isPending("c"));
		assertFalse(state.complete(first, "a", "late"));
	}

	@Test public void cancelRejectsLateCompletionAndEmptyLoadFinishes() {
		ProgressiveLoadState<String> state = new ProgressiveLoadState<>();
		int generation = state.begin(Arrays.asList("a"), value -> false);
		state.cancel();
		assertFalse(state.complete(generation, "a", "late"));
		assertTrue(state.snapshot().isEmpty());
		state.begin(Arrays.asList(), value -> false);
		assertEquals(0, state.pendingCount());
		assertEquals(0, state.totalCount());
	}
}
