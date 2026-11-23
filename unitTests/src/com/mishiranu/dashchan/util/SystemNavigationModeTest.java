package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import androidx.core.graphics.Insets;
import org.junit.Test;

public class SystemNavigationModeTest {
	@Test public void phoneGesturesHiddenIndicatorAndTabletTaskbarRemainGestures() {
		assertEquals(SystemNavigationMode.Mode.GESTURES, SystemNavigationMode.classify(
				Insets.of(0, 0, 0, 64), Insets.of(0, 160, 0, 0), Insets.of(80, 160, 80, 64)));
		assertEquals(SystemNavigationMode.Mode.GESTURES, SystemNavigationMode.classify(
				Insets.NONE, Insets.NONE, Insets.of(80, 0, 80, 0)));
		assertEquals(SystemNavigationMode.Mode.GESTURES, SystemNavigationMode.classify(
				Insets.of(0, 0, 0, 96), Insets.of(0, 32, 0, 96), Insets.of(24, 32, 24, 96)));
	}
	@Test public void buttonNavigationWorksInPortraitAndBothLandscapeSides() {
		for (Insets nav : new Insets[]{Insets.of(0, 0, 0, 90), Insets.of(90, 0, 0, 0), Insets.of(0, 0, 90, 0)}) {
			assertEquals(SystemNavigationMode.Mode.BUTTONS, SystemNavigationMode.classify(nav, nav, Insets.of(0, 68, 0, 90)));
		}
	}
	@Test public void noNavigationInsetsIsUnknownAndStatusBarDoesNotMeanButtons() {
		assertEquals(SystemNavigationMode.Mode.UNKNOWN, SystemNavigationMode.classify(Insets.NONE, Insets.NONE, Insets.NONE));
		assertEquals(SystemNavigationMode.Mode.GESTURES, SystemNavigationMode.classify(
				Insets.of(0, 0, 0, 64), Insets.of(0, 100, 0, 0), Insets.of(0, 100, 0, 64)));
	}
	@Test public void predictiveBackRequiresSupportedAndroidAndSystemGestures() {
		assertTrue(SystemNavigationMode.supportsPredictiveBack(33, true, SystemNavigationMode.Mode.GESTURES));
		assertTrue(SystemNavigationMode.supportsPredictiveBack(36, true, SystemNavigationMode.Mode.GESTURES));
		assertFalse(SystemNavigationMode.supportsPredictiveBack(36, true, SystemNavigationMode.Mode.BUTTONS));
		assertFalse(SystemNavigationMode.supportsPredictiveBack(36, true, SystemNavigationMode.Mode.UNKNOWN));
		assertFalse(SystemNavigationMode.supportsPredictiveBack(32, true, SystemNavigationMode.Mode.GESTURES));
		assertFalse(SystemNavigationMode.supportsPredictiveBack(36, false, SystemNavigationMode.Mode.GESTURES));
	}
}
