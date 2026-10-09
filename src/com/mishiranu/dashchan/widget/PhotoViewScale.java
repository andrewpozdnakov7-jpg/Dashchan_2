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
			// Preserve the historical fit cap and explicit video zoom limit.
			float limit = maximumScaleFactor > 1f
					? Math.min(postScale, defaultMaximum) * maximumScaleFactor : defaultMaximum;
			// Automatic fitting is the baseline, not a user zoom. This must also be
			// the scale applied on reset and when the opening animation finishes.
			initial = Math.min(postScale, limit);
			// Keep the fitted baseline, but allow a photo already at the old upscale cap to zoom.
			maximum = maximumScaleFactor > 1f ? limit : Math.max(limit, initial * 2f);
			doubleTap = Math.min(maximum, Math.max(initial * 2f, Math.min(1f / baseScale, 8f)));
		}
	}

	static float doubleTapTarget(float current, float initial, float enlarged) {
		return isZoomed(current, initial) ? initial : enlarged;
	}

	static boolean isZoomed(float current, float initial) {
		return current > initial + 0.001f;
	}
}
