package com.mishiranu.dashchan.util;

/** Input-specific posting Back policy. No OEM navigation-mode settings or mutable global input flag. */
public final class PostingBackPolicy {
	private PostingBackPolicy() {}
	public static boolean canPrioritizeIme(int api, boolean predictiveEnabled) {
		// Before API 34 there is no animation-start event identifying an actual edge swipe.
		return api >= 34 && predictiveEnabled;
	}
	public static boolean isPredictiveGesture(int api, boolean predictiveEnabled, int swipeEdge, float touchX, float touchY) {
		// BackEvent/BackEventCompat: LEFT=0, RIGHT=1; NONE=2 (buttons on API 36+).
		return canPrioritizeIme(api, predictiveEnabled) && (swipeEdge == 0 || swipeEdge == 1)
				&& Float.isFinite(touchX) && Float.isFinite(touchY);
	}
	public static boolean shouldHideIme(boolean posting, boolean predictiveGesture, boolean imeVisible) {
		return imeVisible && (!posting || !predictiveGesture);
	}
}
