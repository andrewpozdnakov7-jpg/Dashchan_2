package com.mishiranu.dashchan.content.security;

/** Monotonic within a boot. A new boot restarts one bounded delay, never a wall-clock deadline. */
public final class PinRetryPolicy {
	public static final long MAX_DELAY = 900_000L;
	private PinRetryPolicy() {}
	public static long delayForFailures(int failures) {
		return failures < 5 ? 0 : Math.min(MAX_DELAY, 30_000L << Math.min(5, failures - 5));
	}
	public static long remaining(long deadlineElapsed, long nowElapsed) {
		return Math.max(0L, Math.min(MAX_DELAY, deadlineElapsed - nowElapsed));
	}
}
