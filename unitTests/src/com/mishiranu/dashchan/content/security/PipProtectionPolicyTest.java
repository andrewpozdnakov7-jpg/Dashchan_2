package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import org.junit.Test;

/** Prepared regression fixtures for the confirmed PiP shielding defect; not a security audit. */
public class PipProtectionPolicyTest {
	@Test public void unlockedVisiblePipIgnoresOrdinaryPauseCoverByDefault() {
		assertFalse(PipProtectionPolicy.cover(true, false, true, false, true));
		assertFalse(PipProtectionPolicy.cover(true, false, true, false, false));
	}
	@Test public void explicitHideCoversUnlockedPipButNotResumedOrdinaryWindows() {
		assertTrue(PipProtectionPolicy.cover(true, false, true, true, false));
		assertFalse(PipProtectionPolicy.cover(true, false, false, true, false));
	}
	@Test public void realLockAlwaysWinsOverPipOptOut() {
		for (boolean pip : new boolean[]{false, true}) {
			for (boolean hidden : new boolean[]{false, true}) {
				assertTrue(PipProtectionPolicy.cover(true, true, pip, hidden, false));
			}
		}
	}
	@Test public void ordinaryBackgroundStillConcealedAndUnprotectedProfileUnaffected() {
		assertTrue(PipProtectionPolicy.cover(true, false, false, false, true));
		assertFalse(PipProtectionPolicy.cover(false, true, true, true, true));
	}
}
