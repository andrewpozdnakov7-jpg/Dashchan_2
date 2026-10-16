package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import org.junit.Test;

public class ScreenCapturePolicyTest {
	@Test public void defaultProtectedProfileBlocksCaptureEvenAfterUnlock() {
		assertTrue(ScreenCapturePolicy.blocked(true, false, true, false));
	}
	@Test public void explicitPermissionOnlyAllowsUnlockedProfile() {
		assertFalse(ScreenCapturePolicy.blocked(true, false, true, true));
		assertTrue(ScreenCapturePolicy.blocked(true, false, false, true));
	}
	@Test public void pendingCleanupAlwaysBlocksRegardlessOfSettings() {
		for (boolean enabled : new boolean[]{false, true}) for (boolean unlocked : new boolean[]{false, true}) {
			for (boolean allowed : new boolean[]{false, true}) {
				assertTrue(ScreenCapturePolicy.blocked(enabled, true, unlocked, allowed));
			}
		}
	}
	@Test public void ordinaryUnprotectedAppRemainsCapturable() {
		assertFalse(ScreenCapturePolicy.blocked(false, false, false, false));
	}
	@Test public void designCaptureExpiresAt3270WithoutExtendingToUnknownVersions() {
		for (String version : new String[]{"3.2.62", "3.2.63", "3.2.64", "3.2.65", "3.2.66", "3.2.67", "3.2.68", "3.2.69"}) {
			assertTrue(ScreenCapturePolicy.designAllowed(version, true, true));
		}
		for (String version : new String[]{null, "", "3.2.61", "3.2.70", "3.2.100", "4.0.0", "3.2.69-beta"}) {
			assertFalse(ScreenCapturePolicy.designAllowed(version, true, true));
		}
	}
	@Test public void designCaptureStillRequiresTheApprovedCheckbox() {
		for (boolean enabled : new boolean[]{false, true}) for (boolean allowed : new boolean[]{false, true}) {
			assertEquals(enabled && allowed, ScreenCapturePolicy.designAllowed("3.2.62", enabled, allowed));
		}
	}
	@Test public void normalPolicyStillProtectsLockedContentAfterDesignModeExpires() {
		assertFalse(ScreenCapturePolicy.designAllowed("3.2.70", true, true));
		assertTrue(ScreenCapturePolicy.blocked(true, false, false, true));
	}
}
