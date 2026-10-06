package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import org.junit.Test;

/** Guard tests only, not an emulation of the OEM window manager. */
public class PipBoundsMonitorTest {
	private static PipBoundsMonitor rotatedToPortrait() {
		PipBoundsMonitor guard = new PipBoundsMonitor();
		guard.observeRotation(1, 1000L);
		guard.observeRotation(0, 2000L);
		return guard;
	}

	private static boolean portrait(PipBoundsMonitor guard, int left, int top, int right, int bottom) {
		return guard.shouldReport(2350L, left, top, right, bottom, 0, 0, 1440, 3168);
	}

	@Test public void detectsAllThreeRecordedStaleLandscapeBounds() {
		assertTrue(portrait(rotatedToPortrait(), 2487, 591, 2928, 1376));
		assertTrue(portrait(rotatedToPortrait(), 2542, 224, 2928, 910));
		assertTrue(portrait(rotatedToPortrait(), 2590, 224, 2928, 826));
	}

	@Test public void detectsReverseRotationToo() {
		PipBoundsMonitor guard = new PipBoundsMonitor();
		guard.observeRotation(0, 1000L);
		guard.observeRotation(1, 2000L);
		assertTrue(guard.shouldReport(2350L, 935, 2143, 1376, 2928, 0, 0, 3168, 1440));
	}

	@Test public void leavesNormalExpandedMovedAndStashedWindowsAlone() {
		PipBoundsMonitor guard = rotatedToPortrait();
		assertFalse(portrait(guard, 801, 1906, 1376, 2928));
		assertFalse(portrait(guard, 720, 224, 1376, 1390));
		assertFalse(portrait(guard, 20, 20, 461, 805));
		assertFalse(portrait(guard, 1400, 224, 1841, 1009));
		assertFalse(portrait(guard, -400, 224, 41, 1009));
	}

	@Test public void doesNotInterceptDismissOrInvalidBounds() {
		PipBoundsMonitor guard = rotatedToPortrait();
		assertFalse(portrait(guard, 1440, 224, 1881, 1009));
		assertFalse(portrait(guard, 400, 3168, 841, 3953));
		assertFalse(portrait(guard, 2487, 591, 2487, 1376));
		assertFalse(guard.shouldReport(2350L, 2487, 591, 2928, 1376, 0, 0, 0, 3168));
		assertFalse(guard.shouldReport(2350L, 2487, 591, 2928, 1376, 0, 0, 1440, 1440));
	}

	@Test public void requiresRecentAxisChangingRotationNotEntryOrHalfTurn() {
		PipBoundsMonitor guard = new PipBoundsMonitor();
		guard.observeRotation(0, 2000L);
		assertFalse(portrait(guard, 2487, 591, 2928, 1376));
		guard.observeRotation(2, 2200L);
		assertFalse(portrait(guard, 2487, 591, 2928, 1376));
		guard = rotatedToPortrait();
		assertFalse(guard.shouldReport(1999L, 2487, 591, 2928, 1376, 0, 0, 1440, 3168));
		assertFalse(guard.shouldReport(5001L, 2487, 591, 2928, 1376, 0, 0, 1440, 3168));
	}

	@Test public void permitsOnlyOneReportPerRotationAndResetDisarms() {
		PipBoundsMonitor guard = rotatedToPortrait();
		assertTrue(portrait(guard, 2487, 591, 2928, 1376));
		guard.markReported();
		guard.observeRotation(0, 2300L);
		assertFalse(portrait(guard, 2487, 591, 2928, 1376));
		guard.observeRotation(1, 2400L);
		guard.observeRotation(0, 2600L);
		assertTrue(guard.shouldReport(2950L, 2542, 224, 2928, 910, 0, 0, 1440, 3168));
		guard.reset();
		assertFalse(portrait(guard, 2487, 591, 2928, 1376));
	}

}
