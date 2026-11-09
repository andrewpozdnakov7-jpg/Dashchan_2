package com.mishiranu.dashchan.widget;

/** Badge presentation on the existing IME clock, independent of icon geometry. */
public final class PostingAttachmentBadgeMotion {
	private PostingAttachmentBadgeMotion() {}

	public static float alpha(float start, boolean visible, float progress) {
		float from = Math.max(0f, Math.min(1f, start));
		float p = Math.max(0f, Math.min(1f, progress));
		if (p == 1f) return visible ? 1f : 0f;
		// Fade out throughout closing; on opening, reveal only during the last third.
		float phase = visible ? Math.max(0f, (p - 2f / 3f) * 3f) : p;
		phase = phase * phase * (3f - 2f * phase);
		return from + ((visible ? 1f : 0f) - from) * phase;
	}
}
