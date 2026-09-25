package com.mishiranu.dashchan.content;

/** Thumbnail-only limits; never used for decoding or saving original attachments. */
public final class ThumbnailSizing {
	public static final int MAX_EDGE = 2048;
	public static final long MAX_PIXELS = 1024L * 1024;
	private static final long MAX_DECODED_PIXELS = MAX_PIXELS * 4;

	private ThumbnailSizing() {}

	public static int[] dimensions(int width, int height, int targetShortSide) {
		if (width <= 0 || height <= 0 || targetShortSide <= 0) return null;
		double scale = Math.min(1d, (double) targetShortSide / Math.min(width, height));
		scale = Math.min(scale, (double) MAX_EDGE / Math.max(width, height));
		scale = Math.min(scale, Math.sqrt((double) MAX_PIXELS / ((long) width * height)));
		return new int[] {Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale))};
	}

	public static int sampleSize(int width, int height, int targetShortSide) {
		int[] target = dimensions(width, height, targetShortSide);
		if (target == null) return 0;
		int sample = 1;
		while (sample <= (1 << 29) && width / (sample * 2) >= target[0] &&
				height / (sample * 2) >= target[1]) {
			sample *= 2;
		}
		// Extreme aspect ratios can have a one-pixel short side. Still bound decoded allocation.
		while (sample <= (1 << 29) && (ceilDivide(width, sample) > MAX_EDGE * 2L ||
				ceilDivide(height, sample) > MAX_EDGE * 2L ||
				ceilDivide(width, sample) * ceilDivide(height, sample) > MAX_DECODED_PIXELS)) {
			sample *= 2;
		}
		return sample;
	}

	private static long ceilDivide(int value, int divisor) {
		return ((long) value + divisor - 1) / divisor;
	}
}
