package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import org.junit.Test;

public class LockScreenMetricsTest {
	@Test public void scalesAgainstTheSafeWindowBeforeFormPadding() {
		assertEquals(150, LockScreenMetrics.biometricCat(320, 1, false));
		assertEquals(169, LockScreenMetrics.biometricCat(360, 1, false));
		assertEquals(185, LockScreenMetrics.biometricCat(393, 1, false));
		assertEquals(194, LockScreenMetrics.biometricCat(412, 1, false));
		assertEquals(200, LockScreenMetrics.biometricCat(480, 1, false));
	}
	@Test public void usesPixelWidthWithoutApplyingDensityTwice() {
		assertEquals(508, LockScreenMetrics.biometricCat(1080, 3, false));
		assertEquals(600, LockScreenMetrics.biometricCat(2400, 3, false));
		assertEquals(288, LockScreenMetrics.biometricCat(1080, 3, true));
	}
	@Test public void compactNeverEnlargesAnAlreadySmallerCat() {
		assertEquals(47, LockScreenMetrics.biometricCat(100, 1, true));
	}
}
