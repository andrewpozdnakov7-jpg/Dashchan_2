package com.mishiranu.dashchan.chan.soyjak;

import android.os.SystemClock;

/**
 * Switch and failure memory for the headless CloudFlare resolver on Soyjak.
 *
 * <p>When {@link #ENABLED} is false the app only continues the session that the user opened
 * in the visible browser (see {@link SoyjakBrowserSession}). When it is true, a site
 * verification is handled by the standard CloudFlare resolver in an offscreen WebView and
 * the request is retransmitted with the clearance cookie.</p>
 *
 * <p>Resolution is not guaranteed: the interactive widget lives in a cross-origin frame, so
 * the injected helper cannot click it. Success depends on CloudFlare accepting the real
 * Chromium WebView without interaction. After an unsuccessful attempt the headless path is
 * suppressed for a while, so every following request does not wait for another timeout.</p>
 */
public final class SoyjakHeadlessChallenge {
	public static final boolean ENABLED = false;

	private static final long SUPPRESS_TIMEOUT = 120000L;

	private static volatile long suppressedUntil;

	private SoyjakHeadlessChallenge() {}

	public static boolean isSuppressed() {
		return SystemClock.elapsedRealtime() < suppressedUntil;
	}

	public static void suppress() {
		suppressedUntil = SystemClock.elapsedRealtime() + SUPPRESS_TIMEOUT;
	}

	public static void reset() {
		suppressedUntil = 0;
	}
}
