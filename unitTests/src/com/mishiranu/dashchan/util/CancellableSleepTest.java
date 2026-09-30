package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class CancellableSleepTest {
	@Test public void preservesFullDelayAndChecksCancellationBetweenSlices() {
		long[] clock = {0};
		List<Long> slices = new ArrayList<>();
		assertTrue(CancellableSleep.await(250, () -> false, () -> clock[0], nanos -> {
			slices.add(TimeUnit.NANOSECONDS.toMillis(nanos));
			clock[0] += nanos;
		}));
		assertEquals(Arrays.asList(100L, 100L, 50L), slices);
		assertEquals(TimeUnit.MILLISECONDS.toNanos(250), clock[0]);
	}

	@Test public void alreadyCancelledDoesNotSleep() {
		assertFalse(CancellableSleep.await(5000, () -> true, () -> 0L,
				nanos -> fail("Cancelled work must not sleep")));
	}

	@Test public void flagOnlyCancellationStopsAfterFirstSlice() {
		long[] clock = {0};
		boolean[] cancelled = {false};
		assertFalse(CancellableSleep.await(5000, () -> cancelled[0], () -> clock[0], nanos -> {
			clock[0] += nanos;
			cancelled[0] = true;
		}));
		assertEquals(TimeUnit.MILLISECONDS.toNanos(100), clock[0]);
	}

	@Test public void oversleepDoesNotAddAnotherDelay() {
		long[] clock = {0};
		int[] sleeps = {0};
		assertTrue(CancellableSleep.await(250, () -> false, () -> clock[0], nanos -> {
			sleeps[0]++;
			clock[0] += TimeUnit.SECONDS.toNanos(1);
		}));
		assertEquals(1, sleeps[0]);
	}

	@Test public void interruptedSleepRestoresInterruptFlag() {
		try {
			assertFalse(CancellableSleep.await(5000, () -> false, () -> 0L, nanos -> {
				throw new InterruptedException();
			}));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test public void existingInterruptStopsWithoutClearingIt() {
		try {
			Thread.currentThread().interrupt();
			assertFalse(CancellableSleep.await(5000, () -> false, () -> 0L,
					nanos -> fail("Interrupted work must not sleep")));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test public void zeroAndNegativeDelaysDoNotSleep() {
		for (long millis : new long[] {0, -1}) {
			assertTrue(CancellableSleep.await(millis, () -> false, () -> 0L,
					nanos -> fail("Empty delay must not sleep")));
		}
	}
}
