package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import org.junit.Test;

public class GalleryFallbackMotionTest {
	@Test public void gestureKeepsNinetyPercentMinimumAndCanReverseToFullSize() {
		assertEquals(1f, GalleryFallbackMotion.previewScale(0f), .00001f);
		assertEquals(.95f, GalleryFallbackMotion.previewScale(.5f), .00001f);
		assertEquals(.9f, GalleryFallbackMotion.previewScale(1f), .00001f);
		assertEquals(1f, GalleryFallbackMotion.previewScale(0f), .00001f);
	}
	@Test public void fadeThroughNeverShowsOutgoingAndIncomingTogether() {
		for (int i = 0; i <= 100; i++) {
			float p = i / 100f;
			assertEquals(0f, GalleryFallbackMotion.outgoingAlpha(p) * GalleryFallbackMotion.incomingAlpha(p), .00001f);
		}
		assertEquals(1f, GalleryFallbackMotion.outgoingAlpha(0f), 0f);
		assertEquals(0f, GalleryFallbackMotion.outgoingAlpha(.35f), .00001f);
		assertEquals(0f, GalleryFallbackMotion.incomingAlpha(.35f), .00001f);
		assertEquals(1f, GalleryFallbackMotion.incomingAlpha(1f), .00001f);
	}
	@Test public void destinationSettlesFromOneHundredTenPercentToItsRealBounds() {
		assertEquals(1.1f, GalleryFallbackMotion.incomingScale(0f), .00001f);
		assertEquals(1.1f, GalleryFallbackMotion.incomingScale(.35f), .00001f);
		assertEquals(1f, GalleryFallbackMotion.incomingScale(1f), .00001f);
	}
	@Test public void invalidProgressCannotProduceNegativeScaleOrUnboundedAlpha() {
		assertEquals(1f, GalleryFallbackMotion.previewScale(Float.NaN), 0f);
		assertEquals(.9f, GalleryFallbackMotion.previewScale(Float.POSITIVE_INFINITY), .00001f);
		assertEquals(1f, GalleryFallbackMotion.outgoingAlpha(-1f), 0f);
		assertEquals(0f, GalleryFallbackMotion.incomingAlpha(-1f), 0f);
		assertEquals(0f, GalleryFallbackMotion.outgoingAlpha(2f), 0f);
		assertEquals(1f, GalleryFallbackMotion.incomingAlpha(2f), 0f);
	}
}
