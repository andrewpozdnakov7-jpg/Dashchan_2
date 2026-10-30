package com.mishiranu.dashchan.widget;

/** Offset-driven presentation; DrawerLayout remains the sole clock and position owner. */
final class DrawerMotionSpec {
	private DrawerMotionSpec() {}
	static float reveal(float offset) {
		float value = Math.max(0f, Math.min(1f, offset));
		return value * value * (3f - 2f * value);
	}
	static float scrimCoefficient(float offset) { return 0.65f + 0.35f * reveal(offset); }
}
