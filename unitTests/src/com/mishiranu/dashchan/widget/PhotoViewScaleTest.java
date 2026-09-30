package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import org.junit.Test;

public class PhotoViewScaleTest {
	private static final float EPSILON = 0.0001f;

	private static PhotoViewScale forSize(int viewWidth, int viewHeight, int mediaWidth, int mediaHeight,
			boolean fitScreen, float factor) {
		float fit = Math.min((float) viewWidth / mediaWidth, (float) viewHeight / mediaHeight);
		return new PhotoViewScale(Math.min(fit, 1f), Math.max(fit, 1f), fitScreen, factor);
	}

	@Test public void smallVideoFitIsNotManualZoom() {
		PhotoViewScale scales = forSize(1440, 3168, 220, 392, false, 10f);
		assertEquals(1440f / 220f, scales.initial, EPSILON);
		assertEquals(scales.doubleTap, scales.initial, EPSILON);
		assertEquals(40f, scales.maximum, EPSILON);
		assertFalse(PhotoViewScale.isZoomed(scales.initial, scales.initial));
		// Regression: the old baseline was 4, although the displayed scale was over 6.
		assertTrue(PhotoViewScale.isZoomed(scales.initial, 4f));
	}

	@Test public void initialFitIsConsistentAcrossMediaSizesAndOrientations() {
		for (int[] view : new int[][] {{1440, 3168}, {3168, 1440}, {360, 800}, {2560, 1600}}) {
			for (int[] media : new int[][] {{220, 392}, {392, 220}, {640, 360}, {1002, 720},
					{16, 16}, {1, 4000}, {4000, 1}, {7680, 4320}}) {
				for (float factor : new float[] {0f, 2f, 10f}) {
					PhotoViewScale scales = forSize(view[0], view[1], media[0], media[1], false, factor);
					float fit = Math.min((float) view[0] / media[0], (float) view[1] / media[1]);
					float appliedOnReset = fit > 1f ? scales.doubleTap : 1f;
					assertEquals(appliedOnReset, scales.initial, EPSILON);
					assertFalse(PhotoViewScale.isZoomed(appliedOnReset, scales.initial));
					assertTrue(scales.initial >= scales.minimum);
					assertTrue(scales.initial <= scales.maximum);
				}
			}
		}
	}

	@Test public void manualZoomStillBlocksPagingUntilReset() {
		for (float factor : new float[] {0f, 10f}) {
			PhotoViewScale scales = forSize(1440, 3168, 640, 360, false, factor);
			assertTrue(PhotoViewScale.isZoomed(scales.initial * 1.2f, scales.initial));
			assertFalse(PhotoViewScale.isZoomed(scales.minimum, scales.initial));
			assertFalse(PhotoViewScale.isZoomed(scales.initial, scales.initial));
			assertFalse(PhotoViewScale.isZoomed(scales.initial + 0.0005f, scales.initial));
		}
	}

	@Test public void defaultPhotoUpscaleLimitIsPreserved() {
		PhotoViewScale scales = forSize(1440, 3168, 220, 392, false, 0f);
		assertEquals(4f, scales.initial, EPSILON);
		assertEquals(4f, scales.doubleTap, EPSILON);
		assertEquals(4f, scales.maximum, EPSILON);
		assertEquals(1f, scales.minimum, EPSILON);
	}

	@Test public void extremelySmallMediaStillRespectsMaximum() {
		PhotoViewScale scales = forSize(1440, 3168, 16, 16, false, 10f);
		assertEquals(40f, scales.initial, EPSILON);
		assertEquals(scales.maximum, scales.initial, EPSILON);
		assertFalse(PhotoViewScale.isZoomed(scales.doubleTap, scales.initial));
	}

	@Test public void largePhotoDoubleTapAndMaximumAreUnchanged() {
		PhotoViewScale scales = forSize(1080, 1920, 4320, 7680, false, 0f);
		assertEquals(1f, scales.initial, EPSILON);
		assertEquals(4f, scales.doubleTap, EPSILON);
		assertEquals(16f, scales.maximum, EPSILON);
	}

	@Test public void disabledZoomUsesSingleFitScale() {
		for (int[] media : new int[][] {{220, 392}, {4320, 7680}}) {
			PhotoViewScale scales = forSize(1440, 3168, media[0], media[1], true, 0f);
			assertEquals(scales.minimum, scales.initial, EPSILON);
			assertEquals(scales.maximum, scales.initial, EPSILON);
			assertEquals(scales.doubleTap, scales.initial, EPSILON);
			assertFalse(PhotoViewScale.isZoomed(scales.initial, scales.initial));
		}
	}
}
