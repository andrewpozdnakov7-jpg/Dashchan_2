package com.mishiranu.dashchan.ui.gallery;

/** Google predictive Back full-screen surface spec; inputs are normalized presentation progress. */
final class GalleryFallbackMotion {
	private GalleryFallbackMotion() {}
	private static float clamp(float value) {
		return Float.isNaN(value) ? 0f : Math.max(0f, Math.min(1f, value));
	}
	static float previewScale(float progress) { return 1f - .1f * clamp(progress); }
	static float outgoingAlpha(float commit) { return 1f - clamp(commit / .35f); }
	static float incomingAlpha(float commit) { return clamp((commit - .35f) / .65f); }
	static float incomingScale(float commit) { return 1.1f - .1f * incomingAlpha(commit); }
}
