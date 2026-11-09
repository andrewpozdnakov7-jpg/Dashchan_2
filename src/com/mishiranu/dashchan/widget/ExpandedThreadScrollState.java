package com.mishiranu.dashchan.widget;

/** Consumed list motion only. Layout, bottom contact and IDLE never invent a reverse scroll. */
final class ExpandedThreadScrollState {
	enum Decision { KEEP, SHOW, HIDE }
	private final int threshold;
	private int direction, distance;

	ExpandedThreadScrollState(int threshold) { this.threshold = Math.max(1, threshold); }

	Decision scroll(int dy, boolean atStart) {
		if (atStart) { reset(); return Decision.SHOW; }
		if (dy == 0) return Decision.KEEP;
		int next = dy > 0 ? 1 : -1;
		if (next != direction) { direction = next; distance = 0; }
		distance = (int) Math.min(threshold, (long) distance + Math.abs((long) dy));
		return distance < threshold ? Decision.KEEP : direction > 0 ? Decision.HIDE : Decision.SHOW;
	}

	void reset() { direction = distance = 0; }
}
