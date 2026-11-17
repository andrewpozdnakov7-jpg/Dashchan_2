package com.mishiranu.dashchan.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class PageCleanupNoticePolicyTest {
	@Test public void passiveDismissalsAllowOnlyThreeLaunches() {
		int shown = 0;
		for (int launch = 0; launch < 3; launch++) {
			assertTrue(PageCleanupNoticePolicy.eligible(shown, false, false, false));
			shown = PageCleanupNoticePolicy.nextCount(shown);
			assertFalse(PageCleanupNoticePolicy.eligible(shown, false, false, true));
		}
		assertEquals(3, shown);
		assertFalse(PageCleanupNoticePolicy.eligible(shown, false, false, false));
	}
	@Test public void explicitChoiceOrAlreadyEnabledFeatureStopsReminder() {
		assertFalse(PageCleanupNoticePolicy.eligible(1, true, false, false));
		assertFalse(PageCleanupNoticePolicy.eligible(0, false, true, false));
	}
	@Test public void restoredLaunchDoesNotCountAsAnotherPresentation() {
		assertFalse(PageCleanupNoticePolicy.eligible(1, false, false, true));
		assertTrue(PageCleanupNoticePolicy.eligible(1, false, false, false));
	}
	@Test public void malformedCountersAreBounded() {
		assertEquals(1, PageCleanupNoticePolicy.nextCount(-10));
		assertEquals(3, PageCleanupNoticePolicy.nextCount(Integer.MAX_VALUE));
		assertFalse(PageCleanupNoticePolicy.eligible(Integer.MAX_VALUE, false, false, false));
	}
}
