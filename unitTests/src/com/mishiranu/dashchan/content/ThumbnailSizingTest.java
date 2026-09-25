package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import org.junit.Test;

public class ThumbnailSizingTest {
	@Test public void ordinaryPhotoKeepsExistingShortSideQuality() {
		assertArrayEquals(new int[] {384, 288}, ThumbnailSizing.dimensions(4000, 3000, 288));
		assertEquals(8, ThumbnailSizing.sampleSize(4000, 3000, 288));
	}

	@Test public void smallImagesAreNotUpscaled() {
		assertArrayEquals(new int[] {40, 30}, ThumbnailSizing.dimensions(40, 30, 288));
		assertEquals(1, ThumbnailSizing.sampleSize(40, 30, 288));
	}

	@Test public void panoramicAndLongImagesAreBounded() {
		for (int[] dimensions : new int[][] {{1, 1000000}, {1000000, 1}, {2000, 100000},
				{Integer.MAX_VALUE, Integer.MAX_VALUE}, {Integer.MAX_VALUE, 1}}) {
			int[] target = ThumbnailSizing.dimensions(dimensions[0], dimensions[1], 288);
			assertTrue(target[0] > 0 && target[1] > 0);
			assertTrue(target[0] <= ThumbnailSizing.MAX_EDGE && target[1] <= ThumbnailSizing.MAX_EDGE);
			assertTrue((long) target[0] * target[1] <= ThumbnailSizing.MAX_PIXELS);
			int sample = ThumbnailSizing.sampleSize(dimensions[0], dimensions[1], 288);
			assertTrue(sample > 0 && (sample & (sample - 1)) == 0);
			long width = ((long) dimensions[0] + sample - 1) / sample;
			long height = ((long) dimensions[1] + sample - 1) / sample;
			assertTrue(width <= 4096 && height <= 4096);
			assertTrue(width * height <= 4 * ThumbnailSizing.MAX_PIXELS);
		}
	}

	@Test public void invalidBoundsNeverTriggerFullDecode() {
		assertNull(ThumbnailSizing.dimensions(-1, 100, 288));
		assertEquals(0, ThumbnailSizing.sampleSize(100, 0, 288));
		assertEquals(0, ThumbnailSizing.sampleSize(100, 100, 0));
	}

	@Test public void targetSizeAndPortraitAreRespected() {
		assertArrayEquals(new int[] {288, 384}, ThumbnailSizing.dimensions(3000, 4000, 288));
		assertArrayEquals(new int[] {144, 192}, ThumbnailSizing.dimensions(3000, 4000, 144));
	}
}
