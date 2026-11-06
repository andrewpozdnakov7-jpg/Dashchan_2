package com.mishiranu.dashchan.util;

/** Nominal animator times at system scale 1x. Both layers use the same release clock. */
public final class ReplySwipeOverlapSpec {
	public static final int RETURN_MS = 160;
	public static final int OVERLAP_MS = 85;
	public static final int OPEN_MS = RETURN_MS - OVERLAP_MS;
	public static final int FADE_MS = 105;
	public static final int END_MS = OPEN_MS + FADE_MS;

	private ReplySwipeOverlapSpec() {}

	public static float offset(float releaseX, float time) {
		if (time <= 0f) return releaseX;
		if (time >= RETURN_MS) return 0f;
		return releaseX * (float) ((1d + Math.cos(Math.PI * time / RETURN_MS)) * .5d);
	}

	public static float incomingAlpha(float time) {
		float p = Math.max(0f, Math.min(1f, (time - OPEN_MS) / FADE_MS));
		return p * p * (3f - 2f * p);
	}
}
