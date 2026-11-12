package com.mishiranu.dashchan.media.compression;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoConversionPolicyTest {
	@Test public void compatibleSdrVideoCanBeCopiedButShrinkingOrHdrCannot() {
		assertTrue(VideoConversionPolicy.canCopyVideo("video/hevc", "video/hevc", false, false));
		assertFalse(VideoConversionPolicy.canCopyVideo("video/hevc", "video/avc", false, false));
		assertFalse(VideoConversionPolicy.canCopyVideo("video/hevc", "video/hevc", true, false));
		assertFalse(VideoConversionPolicy.canCopyVideo("video/hevc", "video/hevc", false, true));
		assertFalse(VideoConversionPolicy.canCopyVideo(null, "video/hevc", false, false));
	}
	@Test public void formatOnlyConversionDoesNotSpendTheWholeUploadAllowance() {
		assertEquals(1_000_000, VideoConversionPolicy.bitrate(1_000_000, "video/avc", "video/hevc", 20_000_000, false));
		assertEquals(2_000_000, VideoConversionPolicy.bitrate(1_000_000, "video/hevc", "video/avc", 20_000_000, false));
	}
	@Test public void actualSizeBudgetStillBoundsCodecConversion() {
		assertEquals(500_000, VideoConversionPolicy.bitrate(1_000_000, "video/hevc", "video/avc", 500_000, false));
		assertEquals(500_000, VideoConversionPolicy.bitrate(1_000_000, "video/avc", "video/hevc", 500_000, true));
	}
}
