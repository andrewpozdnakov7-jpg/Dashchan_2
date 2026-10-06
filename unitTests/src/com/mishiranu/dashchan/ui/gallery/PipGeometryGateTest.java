package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import org.junit.Test;

/** Snapshot validation only; not a simulation of the system's menu animation. */
public class PipGeometryGateTest {
	@Test public void acceptsCompletedLayoutAtEitherOrientationAndUserSize() {
		assertTrue(PipGeometryGate.matchesLayout(441, 785, 441, 785));
		assertTrue(PipGeometryGate.matchesLayout(576, 1024, 576, 1024));
		assertTrue(PipGeometryGate.matchesLayout(1440, 3168, 1440, 3168));
		assertTrue(PipGeometryGate.matchesLayout(3168, 1440, 3168, 1440));
	}

	@Test public void rejectsOldLayoutDuringResizeAndExit() {
		assertFalse(PipGeometryGate.matchesLayout(576, 1024, 441, 785));
		assertFalse(PipGeometryGate.matchesLayout(1440, 3168, 3168, 1440));
		assertFalse(PipGeometryGate.matchesLayout(1440, 3168, 441, 785));
		assertFalse(PipGeometryGate.matchesLayout(0, 0, 0, 0));
	}

	@Test public void rejectsBothRecordedOffscreenAnimationDestinations() {
		assertFalse(portrait(2487, 591, 2928, 1376));
		assertFalse(portrait(2541, 224, 2928, 912));
		assertFalse(PipGeometryGate.intersectsDisplay(935, 2143, 1376, 2928, 0, 0, 3168, 1440));
	}

	@Test public void permitsNormalMovedResizedAndDeliberatelyStashedWindows() {
		assertTrue(portrait(801, 1906, 1376, 2928));
		assertTrue(portrait(720, 224, 1376, 1390));
		assertTrue(portrait(20, 20, 461, 805));
		assertTrue(portrait(-400, 224, 41, 1009));
		assertTrue(portrait(1400, 224, 1841, 1009));
	}

	@Test public void rejectsEmptyWindowsDisplaysAndDismissedWindows() {
		assertFalse(portrait(100, 100, 100, 400));
		assertFalse(portrait(1440, 100, 1800, 400));
		assertFalse(PipGeometryGate.intersectsDisplay(100, 100, 400, 400, 0, 0, 0, 0));
		assertTrue(PipGeometryGate.intersectsDisplay(120, 120, 400, 400, 100, 100, 1540, 3268));
	}

	private static boolean portrait(int left, int top, int right, int bottom) {
		return PipGeometryGate.intersectsDisplay(left, top, right, bottom, 0, 0, 1440, 3168);
	}
}
