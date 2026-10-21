package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.graphics.Color;
import android.view.WindowInsetsController;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared source fixtures only; no Activity/user data and no test execution in this handoff. */
@RunWith(AndroidJUnit4.class)
public class ExpandedScreenStatusBarTest {
	@Test public void shownChromeUsesItsExactSurfaceAndChoosesContrastingIcons() {
		for (int surface : new int[]{Color.WHITE, Color.BLACK, 0xfff3f5fa, 0xff161c24, 0xff49a183}) {
			int result = ExpandedScreen.visibleStatusBarColor(surface, 0xff234567, 255, 0, false, Color.BLACK);
			assertEquals(surface, result);
			int chosen = ExpandedScreen.prefersDarkStatusBarIcons(result) ? Color.BLACK : Color.WHITE;
			int other = chosen == Color.BLACK ? Color.WHITE : Color.BLACK;
			assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(chosen, result)
					>= androidx.core.graphics.ColorUtils.calculateContrast(other, result));
		}
		assertTrue(ExpandedScreen.prefersDarkStatusBarIcons(Color.WHITE));
		assertFalse(ExpandedScreen.prefersDarkStatusBarIcons(Color.BLACK));
	}
	@Test public void fadingChromeAndActionModeGuardUseVisibleCompositedBackground() {
		assertEquals(0xff123456, ExpandedScreen.visibleStatusBarColor(Color.WHITE, 0xff123456, 0, 0, false, Color.BLACK));
		int guard = ExpandedScreen.visibleStatusBarColor(Color.WHITE, Color.WHITE, 255, 0xee000000, false, Color.WHITE);
		assertFalse(ExpandedScreen.prefersDarkStatusBarIcons(guard));
		assertEquals(Color.WHITE, ExpandedScreen.visibleStatusBarColor(Color.WHITE, Color.WHITE, 255, 0, false, Color.BLACK));
	}
	@Test public void drawerUsesItsOwnSurfaceAndClosingReturnsToToolbar() {
		assertEquals(Color.BLACK, ExpandedScreen.visibleStatusBarColor(Color.WHITE, Color.WHITE, 255, 0, true, Color.BLACK));
		assertEquals(Color.WHITE, ExpandedScreen.visibleStatusBarColor(Color.WHITE, Color.WHITE, 255, 0, false, Color.BLACK));
	}
	@Test public void statusIconPolicyPreservesNavigationAndUnrelatedAppearanceFlags() {
		int untouched = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS | 0x1000;
		int dark = ExpandedScreen.statusBarIconAppearance(untouched, true);
		assertEquals(untouched | WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, dark);
		assertEquals(untouched, ExpandedScreen.statusBarIconAppearance(dark, false));
		assertEquals(dark, ExpandedScreen.statusBarIconAppearance(dark, true));
	}
}
