package com.mishiranu.dashchan.util;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Retains the backoff deadline while observing flag-only cancellation at most every 100 ms. */
public final class CancellableSleep {
	private CancellableSleep() {}

	@FunctionalInterface
	interface Sleeper {
		void sleep(long nanos) throws InterruptedException;
	}

	public static boolean await(long millis, BooleanSupplier cancelled) {
		return await(millis, cancelled, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
	}

	static boolean await(long millis, BooleanSupplier cancelled, LongSupplier clock, Sleeper sleeper) {
		long duration = TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
		long started = clock.getAsLong();
		while (true) {
			if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) return false;
			long remaining = duration - (clock.getAsLong() - started);
			if (remaining <= 0) return true;
			try {
				sleeper.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}
}
