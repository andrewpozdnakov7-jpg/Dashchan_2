package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import org.junit.Test;

public class PostingAttachmentBadgeMotionTest {
	@Test public void closingBeginsFadingImmediatelyAndFinishesWithTheKeyboard() {
		assertEquals(1f, PostingAttachmentBadgeMotion.alpha(1f, false, 0f), 0f);
		assertTrue(PostingAttachmentBadgeMotion.alpha(1f, false, .1f) < 1f);
		assertEquals(.5f, PostingAttachmentBadgeMotion.alpha(1f, false, .5f), .0001f);
		assertEquals(0f, PostingAttachmentBadgeMotion.alpha(1f, false, 1f), 0f);
	}

	@Test public void openingWaitsUntilTheLastThirdAndEndsFullyVisible() {
		assertEquals(0f, PostingAttachmentBadgeMotion.alpha(0f, true, 0f), 0f);
		assertEquals(0f, PostingAttachmentBadgeMotion.alpha(0f, true, .5f), 0f);
		assertEquals(0f, PostingAttachmentBadgeMotion.alpha(0f, true, 2f / 3f), 0f);
		assertTrue(PostingAttachmentBadgeMotion.alpha(0f, true, .8f) > 0f);
		assertEquals(1f, PostingAttachmentBadgeMotion.alpha(0f, true, 1f), .0001f);
	}

	@Test public void reversalStartsAtTheCurrentAlphaAndKeepsItDuringTheOpeningDelay() {
		float current = PostingAttachmentBadgeMotion.alpha(1f, false, .45f);
		assertEquals(current, PostingAttachmentBadgeMotion.alpha(current, true, 0f), 0f);
		assertEquals(current, PostingAttachmentBadgeMotion.alpha(current, true, .5f), 0f);
		float reopening = PostingAttachmentBadgeMotion.alpha(current, true, .85f);
		assertTrue(reopening > current);
		assertEquals(reopening, PostingAttachmentBadgeMotion.alpha(reopening, false, 0f), 0f);
	}

	@Test public void everyStartAlphaHasMonotonicBoundedMotionAndExactEndpoints() {
		for (boolean visible : new boolean[]{false, true}) for (float start : new float[]{0f, .25f, .6f, 1f}) {
			float previous = start;
			for (int i = 0; i <= 100; i++) {
				float alpha = PostingAttachmentBadgeMotion.alpha(start, visible, i / 100f);
				assertTrue(alpha >= 0f && alpha <= 1f);
				assertTrue(visible ? alpha >= previous : alpha <= previous);
				previous = alpha;
			}
			assertEquals(visible ? 1f : 0f, previous, .0001f);
		}
	}
}
