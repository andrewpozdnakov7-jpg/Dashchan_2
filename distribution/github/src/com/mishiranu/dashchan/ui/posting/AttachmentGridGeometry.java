package com.mishiranu.dashchan.ui.posting;

/** Measurements in the same units (pixels at runtime); no screen-width/tablet heuristic. */
final class AttachmentGridGeometry {
	private AttachmentGridGeometry() {}
	static int columns(int width, int gap, int minimum) {
		return (long) width >= 2L * minimum + gap ? 2 : 1;
	}
	static int cardWidth(int width, int gap, int minimum) {
		return Math.max(0, (width - (columns(width, gap, minimum) - 1) * gap)
				/ columns(width, gap, minimum));
	}
}
