package com.mishiranu.dashchan.ui.gallery;

/** Return-to-grid policy, evaluated only after the grid's layout has settled. */
final class GalleryGridReturnPolicy {
	private GalleryGridReturnPolicy() {}

	static boolean shouldReveal(int target, int firstVisible, int lastVisible, boolean unchanged) {
		if (target < 0 || unchanged) return false;
		// A partially visible row also belongs to the user's viewport. Do not
		// replace their pixel offset merely to align that row with the toolbar.
		return firstVisible < 0 || lastVisible < firstVisible
				|| target < firstVisible || target > lastVisible;
	}
}
