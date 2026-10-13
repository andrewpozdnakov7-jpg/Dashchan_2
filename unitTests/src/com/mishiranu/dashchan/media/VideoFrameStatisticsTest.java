package com.mishiranu.dashchan.media;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VideoFrameStatisticsTest {
	@Test public void opaqueBlackIsDistinctFromAnEmptyTransparentDestination() {
		assertEquals("samples=2 opaque_permille=1000 mean_luma=0 near_black_permille=1000",
				VideoFrameStatistics.describe(new int[] {0xff000000, 0xff000000}));
		assertEquals("samples=2 opaque_permille=0 mean_luma=-1 near_black_permille=-1",
				VideoFrameStatistics.describe(new int[] {0, 0x00ffffff}));
	}

	@Test public void brightnessAndBlackFractionAreAggregateOnly() {
		assertEquals("samples=2 opaque_permille=1000 mean_luma=127 near_black_permille=500",
				VideoFrameStatistics.describe(new int[] {0xff000000, 0xffffffff}));
	}

	@Test public void nearBlackThresholdDoesNotClassifyBrightSingleChannelsAsBlack() {
		assertEquals("samples=2 opaque_permille=1000 mean_luma=6 near_black_permille=500",
				VideoFrameStatistics.describe(new int[] {0xff0c0c0c, 0xff00000d}));
	}

	@Test public void transparentPixelsAreExcludedAndInputIsNotChanged() {
		int[] pixels = {0x00ffffff, 0xffffffff};
		assertEquals("samples=2 opaque_permille=500 mean_luma=255 near_black_permille=0",
				VideoFrameStatistics.describe(pixels));
		assertEquals(0x00ffffff, pixels[0]);
		assertEquals(0xffffffff, pixels[1]);
	}

	@Test public void emptySamplesHaveNoColorConclusion() {
		assertEquals("samples=0", VideoFrameStatistics.describe(new int[0]));
	}
}
