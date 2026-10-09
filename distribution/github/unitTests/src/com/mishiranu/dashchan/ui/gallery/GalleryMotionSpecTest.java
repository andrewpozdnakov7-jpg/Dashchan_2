package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import org.junit.Test;

public class GalleryMotionSpecTest {
	@Test public void gridAndPhotoNeverOverlapDuringFadeThrough() {
		for (int i = 0; i <= 1000; i++) {
			float clock = i / 1000f;
			float outgoing = GalleryMotionSpec.outgoingAlpha(clock), incoming = GalleryMotionSpec.incomingAlpha(clock);
			assertTrue(outgoing >= 0f && outgoing <= 1f); assertTrue(incoming >= 0f && incoming <= 1f);
			assertFalse(outgoing > 0f && incoming > 0f);
			if (Math.abs(clock - .3f) > .00001f) assertTrue(outgoing > 0f || incoming > 0f);
		}
		assertEquals(1f, GalleryMotionSpec.outgoingAlpha(0f), 0f);
		assertEquals(0f, GalleryMotionSpec.incomingAlpha(0f), 0f);
		assertEquals(0f, GalleryMotionSpec.outgoingAlpha(1f), 0f);
		assertEquals(1f, GalleryMotionSpec.incomingAlpha(1f), .00001f);
	}
}
