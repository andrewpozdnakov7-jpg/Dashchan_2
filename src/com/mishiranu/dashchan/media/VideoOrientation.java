package com.mishiranu.dashchan.media;

import java.util.Arrays;

/** Stream orientation only: coded buffers stay unrotated, presentation owns one clockwise turn. */
final class VideoOrientation {
	private VideoOrientation() {}

	static int normalize(int clockwise) {
		int angle = clockwise % 360;
		if (angle < 0) angle += 360;
		return angle % 90 == 0 ? angle : 0;
	}

	static boolean swapsDimensions(int clockwise) {
		int angle = normalize(clockwise);
		return angle == 90 || angle == 270;
	}

	/** TextureView first stretches its buffer to the View; map that rectangle to rotated unit UVs. */
	static void textureTransform(float[] values, int width, int height, int clockwise) {
		Arrays.fill(values, 0f);
		values[0] = values[4] = values[8] = 1f;
		if (width <= 0 || height <= 0) return;
		switch (normalize(clockwise)) {
			case 90:
				values[0] = values[4] = 0f;
				values[1] = -(float) width / height;
				values[2] = width;
				values[3] = (float) height / width;
				break;
			case 180:
				values[0] = values[4] = -1f;
				values[2] = width;
				values[5] = height;
				break;
			case 270:
				values[0] = values[4] = 0f;
				values[1] = (float) width / height;
				values[3] = -(float) height / width;
				values[5] = height;
				break;
		}
	}
}
