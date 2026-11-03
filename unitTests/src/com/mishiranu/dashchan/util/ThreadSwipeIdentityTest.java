package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ThreadSwipeIdentityTest {
	private final ThreadMotionKey a = new ThreadMotionKey("board", "a", "123");
	private final ThreadMotionKey b = new ThreadMotionKey("board", "b", "123");

	@Test public void confirmationConsumesExactlyOneGesture() {
		ThreadSwipeIdentity swipe = new ThreadSwipeIdentity(a, 5);
		int writes = 0;
		if (swipe.consume(a, 5)) writes++;
		if (swipe.consume(a, 5)) writes++;
		swipe.advanceAfterRelatedHide(6);
		if (swipe.consume(a, 6)) writes++;
		assertEquals(1, writes);
	}
	@Test public void cancellationCannotBeRevivedByAnotherHide() {
		ThreadSwipeIdentity swipe = new ThreadSwipeIdentity(a, 5);
		swipe.cancel(); swipe.advanceAfterRelatedHide(6);
		assertFalse(swipe.consume(a, 5)); assertFalse(swipe.consume(a, 6));
	}
	@Test public void stalePositionMustNeverHideAnotherBoardOrAnotherDataset() {
		ThreadSwipeIdentity swipe = new ThreadSwipeIdentity(a, 5);
		List<ThreadMotionKey> reordered = Arrays.asList(b, a);
		assertFalse(swipe.consume(reordered.get(0), 5));
		assertFalse(swipe.consume(reordered.get(1), 6));
		assertFalse(swipe.consume(new ThreadMotionKey("another_source", "a", "123"), 5));
	}
	@Test public void aRelatedConfirmedHideCanMoveTheSecondLiveGesture() {
		ThreadSwipeIdentity first = new ThreadSwipeIdentity(a, 5), second = new ThreadSwipeIdentity(b, 5);
		assertTrue(first.consume(a, 5));
		first.advanceAfterRelatedHide(6); second.advanceAfterRelatedHide(6);
		assertFalse(first.consume(a, 6)); assertTrue(second.consume(b, 6));
		assertFalse(second.consume(b, 6));
	}
}
