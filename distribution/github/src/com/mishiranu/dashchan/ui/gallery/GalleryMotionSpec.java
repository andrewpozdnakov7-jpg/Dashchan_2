package com.mishiranu.dashchan.ui.gallery;

/** One geometry/scrim clock; no independent stretch of an image's two axes. */
public final class GalleryMotionSpec {
	public static final int ENTER = 320, EXIT = 240, MODE = 300, REVEAL = 160;
	private GalleryMotionSpec() {}
	public static float lerp(float from, float to, float progress) { return from + (to - from) * progress; }
	public static float outgoingAlpha(float clock) { return Math.max(0f, Math.min(1f, 1f - clock / .3f)); }
	public static float incomingAlpha(float clock) { return Math.max(0f, Math.min(1f, (clock - .3f) / .7f)); }
}
