package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import org.junit.Test;

public class VideoOrientationTest {
	@Test public void signedQuarterTurnsAndInvalidAnglesHaveSafeNormalization() {
		assertEquals(0, VideoOrientation.normalize(0));
		assertEquals(90, VideoOrientation.normalize(450));
		assertEquals(270, VideoOrientation.normalize(-90));
		assertEquals(180, VideoOrientation.normalize(-180));
		assertEquals(0, VideoOrientation.normalize(720));
		assertEquals(0, VideoOrientation.normalize(45));
		assertEquals(0, VideoOrientation.normalize(Integer.MIN_VALUE));
	}

	@Test public void onlyQuarterTurnsExchangeCodedAndDisplayDimensions() {
		assertFalse(VideoOrientation.swapsDimensions(0));
		assertTrue(VideoOrientation.swapsDimensions(90));
		assertFalse(VideoOrientation.swapsDimensions(180));
		assertTrue(VideoOrientation.swapsDimensions(270));
		assertTrue(VideoOrientation.swapsDimensions(-90));
		assertFalse(VideoOrientation.swapsDimensions(45));
	}

	private static void assertCorners(int width, int height, int rotation, float[] expected) {
		float[] matrix = new float[9];
		VideoOrientation.textureTransform(matrix, width, height, rotation);
		float[] corners = {0, 0, width, 0, width, height, 0, height};
		for (int i = 0; i < corners.length; i += 2) {
			float x = matrix[0] * corners[i] + matrix[1] * corners[i + 1] + matrix[2];
			float y = matrix[3] * corners[i] + matrix[4] * corners[i + 1] + matrix[5];
			assertEquals(expected[i], x, 0.001f);
			assertEquals(expected[i + 1], y, 0.001f);
		}
	}

	@Test public void suppliedPhoneVideoGetsClockwisePortraitMappingWithoutStretchOrCrop() {
		// The supplied 1560x720 coded frame has ffprobe rotation=-90 (90 clockwise).
		assertCorners(720, 1560, 90, new float[] {720, 0, 720, 1560, 0, 1560, 0, 0});
	}

	@Test public void allFourOrientationsMapExactlyOntoTheSameViewRectangle() {
		assertCorners(320, 180, 0, new float[] {0, 0, 320, 0, 320, 180, 0, 180});
		assertCorners(320, 180, 90, new float[] {320, 0, 320, 180, 0, 180, 0, 0});
		assertCorners(320, 180, 180, new float[] {320, 180, 0, 180, 0, 0, 320, 0});
		assertCorners(320, 180, 270, new float[] {0, 180, 0, 0, 320, 0, 320, 180});
		// A recreated/resized gallery or PiP view gets a fresh transform in its own coordinates.
		assertCorners(180, 390, 90, new float[] {180, 0, 180, 390, 0, 390, 0, 0});
	}

	@Test public void missingSizeAndUnsupportedRotationResetAnOldTransform() {
		float[] matrix = new float[9];
		VideoOrientation.textureTransform(matrix, 720, 1560, 90);
		VideoOrientation.textureTransform(matrix, 0, 0, 90);
		assertArrayEquals(new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, matrix, 0f);
		VideoOrientation.textureTransform(matrix, 720, 1560, 45);
		assertArrayEquals(new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, matrix, 0f);
	}

	@Test public void repeatedLayoutAndSurfaceUpdatesDoNotAccumulateRotation() {
		float[] matrix = new float[9];
		VideoOrientation.textureTransform(matrix, 720, 1560, 90);
		float[] first = matrix.clone();
		for (int update = 0; update < 4; update++) {
			VideoOrientation.textureTransform(matrix, 720, 1560, 90);
			assertArrayEquals(first, matrix, 0f);
		}
	}
}
