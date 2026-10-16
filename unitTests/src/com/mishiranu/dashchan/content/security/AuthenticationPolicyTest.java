package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import org.junit.Test;

public class AuthenticationPolicyTest {
	@Test public void legacyRecordsKeepTheirExistingMethodsWithoutEnablingAnUnconfiguredLock() {
		assertNull(AuthenticationPolicy.read(null, false, false));
		try { AuthenticationPolicy.read(null, false, true); fail("Orphaned biometric flag must fail closed"); }
		catch (IllegalArgumentException expected) {}
		assertEquals(AuthenticationPolicy.Mode.PIN_ONLY, AuthenticationPolicy.read(null, true, false));
		assertEquals(AuthenticationPolicy.Mode.PIN_OR_BIOMETRIC, AuthenticationPolicy.read(null, true, true));
	}
	@Test public void biometricOnlyHasNoPinFallbackAndCombinedModeAllowsEitherMethod() {
		for (AuthenticationPolicy.Mode mode : AuthenticationPolicy.Mode.values()) {
			assertEquals(mode, AuthenticationPolicy.read(mode.value, mode.pin, mode.biometric));
		}
		assertFalse(AuthenticationPolicy.Mode.BIOMETRIC_ONLY.pin);
		assertTrue(AuthenticationPolicy.Mode.BIOMETRIC_ONLY.biometric);
		assertTrue(AuthenticationPolicy.Mode.PIN_OR_BIOMETRIC.pin);
		assertTrue(AuthenticationPolicy.Mode.PIN_OR_BIOMETRIC.biometric);
	}
	@Test public void unknownOrIncompleteExplicitRecordsFailRatherThanDisablingProtection() {
		for (String value : new String[]{"", "unknown", "PIN", "biometric"}) {
			try { AuthenticationPolicy.read(value, false, false); fail("Invalid mode accepted"); }
			catch (IllegalArgumentException expected) {}
		}
		for (AuthenticationPolicy.Mode mode : AuthenticationPolicy.Mode.values()) {
			try { AuthenticationPolicy.read(mode.value, !mode.pin, mode.biometric); fail("Wrong PIN state"); }
			catch (IllegalArgumentException expected) {}
			try { AuthenticationPolicy.read(mode.value, mode.pin, !mode.biometric); fail("Wrong biometric state"); }
			catch (IllegalArgumentException expected) {}
		}
	}
}
