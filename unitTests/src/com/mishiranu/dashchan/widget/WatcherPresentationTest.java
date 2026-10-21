package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import org.junit.Test;

public class WatcherPresentationTest {
	@Test public void disabledMasksSharedReplyTrackingFields() {
		WatcherPresentation p = WatcherPresentation.of(false, false, true, 42, false, true);
		assertFalse(p.enabled); assertEquals(0, p.count); assertEquals("0", p.text);
		assertEquals(WatcherPresentation.Mode.DISABLED, p.mode);
	}
	@Test public void unavailableIsStillCheckedAndKeepsItsExactCount() {
		WatcherPresentation p = WatcherPresentation.of(true, true, true, 1234, false, false);
		assertTrue(p.enabled); assertEquals(1234, p.count); assertEquals("1K+", p.text);
		assertEquals(WatcherPresentation.Mode.UNAVAILABLE, p.mode);
	}
	@Test public void deletedWinsEvenWithErrorAndRunningButDoesNotLoseMonitoringState() {
		WatcherPresentation p = WatcherPresentation.of(true, true, true, 4, true, true);
		assertTrue(p.enabled); assertEquals("+4", p.text); assertEquals(WatcherPresentation.Mode.DELETED, p.mode);
		p = WatcherPresentation.of(false, false, true, 99, true, true);
		assertFalse(p.enabled); assertEquals("0", p.text); assertEquals(WatcherPresentation.Mode.DELETED, p.mode);
	}
	@Test public void errorsWinOverCheckingAndAreNotUnknownNumbers() {
		WatcherPresentation p = WatcherPresentation.of(true, false, true, 0, false, true);
		assertEquals(WatcherPresentation.Mode.ERROR, p.mode); assertEquals("0", p.text);
	}
	@Test public void runningPreservesNumberAndNegativeDoesNotBecomeUnread() {
		assertEquals(WatcherPresentation.Mode.CHECKING,
				WatcherPresentation.of(true, false, true, 12, false, false).mode);
		WatcherPresentation p = WatcherPresentation.of(true, false, false, -3, false, false);
		assertEquals(WatcherPresentation.Mode.ACTIVE, p.mode); assertEquals("−3", p.text); assertEquals(-3, p.count);
		p = WatcherPresentation.of(true, false, false, -3, true, false);
		assertEquals("0", p.text); assertEquals(-3, p.count);
	}
	@Test public void compactFormatCoversBoundariesAndBothIntegerExtremes() {
		int[] input = {0, 1, 999, 1000, 1999, 2000, 999999, 1000000, -3, -1000, Integer.MAX_VALUE, Integer.MIN_VALUE};
		String[] expected = {"0", "+1", "+999", "1K+", "1K+", "2K+", "999K+", "1M+", "−3", "−1K", "2B+", "−2B"};
		for (int i = 0; i < input.length; i++) assertEquals("value=" + input[i], expected[i], WatcherPresentation.format(input[i]));
	}
}
