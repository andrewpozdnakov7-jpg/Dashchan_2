package com.mishiranu.dashchan.widget;

/** Scale policy shared by photos and video surfaces; independent of Android for regression tests. */
final class PhotoViewScale {
	final float minimum;
	final float maximum;
	final float initial;
	final float doubleTap;

	PhotoViewScale(float baseScale, float postScale, boolean fitScreen, float maximumScaleFactor) {
		if (fitScreen) {
			minimum = maximum = initial = doubleTap = postScale;
		} else {
			minimum = 1f;
			float defaultMaximum = 4f / baseScale;
			// Preserve existing zoom limits, including the default photo upscale cap.
			maximum = maximumScaleFactor > 1f
					? Math.min(postScale, defaultMaximum) * maximumScaleFactor : defaultMaximum;
			// Automatic fitting is the baseline, not a user zoom. This must also be
			// the scale applied on reset and when the opening animation finishes.
			initial = Math.min(postScale, maximum);
			doubleTap = postScale > 1f ? initial : Math.min(1f / baseScale, 8f);
		}
	}

	static boolean isZoomed(float current, float initial) {
		return current > initial + 0.001f;
	}
}
