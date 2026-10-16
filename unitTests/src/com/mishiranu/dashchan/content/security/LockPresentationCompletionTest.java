package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class LockPresentationCompletionTest {
	@Test public void animatorAndDeadlineCannotContinueTwice() {
		AtomicInteger calls = new AtomicInteger();
		LockPresentationCompletion completion = new LockPresentationCompletion(calls::incrementAndGet);
		completion.finish(() -> true); completion.finish(() -> true);
		assertEquals(1, calls.get());
	}
	@Test public void pauseCancellationDiscardsLateAnimationAndDeadline() {
		AtomicInteger calls = new AtomicInteger();
		LockPresentationCompletion completion = new LockPresentationCompletion(calls::incrementAndGet);
		completion.cancel(); completion.cancel(); completion.finish(() -> true);
		assertEquals(0, calls.get());
	}
	@Test public void staleLeaseCannotBecomeValidAfterReentry() {
		AtomicInteger calls = new AtomicInteger();
		LockPresentationCompletion completion = new LockPresentationCompletion(calls::incrementAndGet);
		completion.finish(() -> false); completion.finish(() -> true);
		assertEquals(0, calls.get());
	}
	@Test public void continuationMayCancelItselfWithoutReentry() {
		AtomicInteger calls = new AtomicInteger();
		LockPresentationCompletion[] holder = new LockPresentationCompletion[1];
		holder[0] = new LockPresentationCompletion(() -> {
			calls.incrementAndGet(); holder[0].finish(() -> true);
		});
		holder[0].finish(() -> true); assertEquals(1, calls.get());
	}
}
