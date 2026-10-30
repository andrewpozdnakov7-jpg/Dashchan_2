package com.mishiranu.dashchan.widget;

/** A shared clock with no overlap between outgoing and incoming post text. */
final class DialogMotionSpec {
	private DialogMotionSpec() {}
	static float outgoingAlpha(float clock) { return Math.max(0f, Math.min(1f, 1f - clock / 0.3f)); }
	static float incomingAlpha(float clock) { return Math.max(0f, Math.min(1f, (clock - 0.3f) / 0.7f)); }
}
