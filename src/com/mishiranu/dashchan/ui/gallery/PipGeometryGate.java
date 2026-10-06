package com.mishiranu.dashchan.ui.gallery;

/** Validates a source snapshot; never calculates or changes the system's PiP position. */
final class PipGeometryGate {
	private PipGeometryGate() {}

	static boolean matchesLayout(int windowWidth, int windowHeight, int viewWidth, int viewHeight) {
		return windowWidth > 0 && windowHeight > 0 && windowWidth == viewWidth && windowHeight == viewHeight;
	}

	static boolean intersectsDisplay(int left, int top, int right, int bottom,
			int displayLeft, int displayTop, int displayRight, int displayBottom) {
		return right > left && bottom > top && displayRight > displayLeft && displayBottom > displayTop
				&& left < displayRight && right > displayLeft && top < displayBottom && bottom > displayTop;
	}
}
