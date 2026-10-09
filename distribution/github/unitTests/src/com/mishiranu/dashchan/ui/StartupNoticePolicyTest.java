package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import org.junit.Test;

public class StartupNoticePolicyTest {
	@Test public void freshInstallAndUpdateHaveIdenticalEligibility() {
		assertTrue(new StartupNoticePolicy().claim(0));
		assertTrue(new StartupNoticePolicy().claim(1));
		assertTrue(new StartupNoticePolicy().claim(2));
		assertFalse(new StartupNoticePolicy().claim(3));
	}
	@Test public void resumeRotationAndRepeatedHostsCannotClaimAgain() {
		StartupNoticePolicy launch = new StartupNoticePolicy();
		assertTrue(launch.claim(0));
		assertFalse(launch.claim(0));
		assertFalse(launch.claim(1));
	}
	@Test public void restoredDialogConsumesLaunchButNotAcknowledgement() {
		StartupNoticePolicy launch = new StartupNoticePolicy();
		launch.restoreVisible();
		assertFalse(launch.claim(0));
		assertTrue(new StartupNoticePolicy().claim(0));
	}
	@Test public void onlyThreeExplicitAcknowledgementsStopFutureLaunches() {
		int count = 0;
		for (int i = 0; i < 3; i++) {
			assertTrue(new StartupNoticePolicy().claim(count));
			count = StartupNoticePolicy.nextCount(count);
		}
		assertEquals(3, count);
		assertFalse(new StartupNoticePolicy().claim(count));
		assertEquals(3, StartupNoticePolicy.nextCount(count));
	}
}
