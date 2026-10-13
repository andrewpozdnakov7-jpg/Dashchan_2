package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import org.junit.Test;

public class VideoPreparationPolicyTest {
	@Test public void speculativeFileBudgetDoesNotAllowEmptyOrUnboundedFiles() {
		assertTrue(VideoPreparationPolicy.fileAllowed(1, false));
		assertTrue(VideoPreparationPolicy.fileAllowed(64L * 1024 * 1024, false));
		assertFalse(VideoPreparationPolicy.fileAllowed(64L * 1024 * 1024 + 1, false));
		assertFalse(VideoPreparationPolicy.fileAllowed(0, false));
		assertFalse(VideoPreparationPolicy.fileAllowed(-1, false));
		assertFalse(VideoPreparationPolicy.fileAllowed(1024, true));
	}

	@Test public void resolutionBudgetIsOrientationIndependentAndCannotOverflow() {
		assertTrue(VideoPreparationPolicy.dimensionsAllowed(1920, 1080));
		assertTrue(VideoPreparationPolicy.dimensionsAllowed(1080, 1920));
		assertFalse(VideoPreparationPolicy.dimensionsAllowed(3840, 2160));
		assertFalse(VideoPreparationPolicy.dimensionsAllowed(Integer.MAX_VALUE, Integer.MAX_VALUE));
		assertFalse(VideoPreparationPolicy.dimensionsAllowed(0, 1080));
		assertFalse(VideoPreparationPolicy.dimensionsAllowed(1920, -1));
	}
}
