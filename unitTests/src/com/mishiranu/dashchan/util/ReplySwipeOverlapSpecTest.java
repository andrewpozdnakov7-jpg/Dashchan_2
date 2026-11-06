package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class ReplySwipeOverlapSpecTest {
	@Test public void formStartsWhileCardIsStillReturning() {
		assertEquals(75, ReplySwipeOverlapSpec.OPEN_MS);
		assertEquals(85, ReplySwipeOverlapSpec.RETURN_MS - ReplySwipeOverlapSpec.OPEN_MS);
		assertEquals(0f, ReplySwipeOverlapSpec.incomingAlpha(75), 0f);
		assertTrue(ReplySwipeOverlapSpec.incomingAlpha(76) > 0f);
		assertEquals(10.24f, ReplySwipeOverlapSpec.offset(56f, 115), .02f);
		assertEquals(.32480f, ReplySwipeOverlapSpec.incomingAlpha(115), .001f);
		assertEquals(0f, ReplySwipeOverlapSpec.offset(56f, 160), 0f);
		assertTrue(ReplySwipeOverlapSpec.incomingAlpha(160) > .9f);
		assertEquals(1f, ReplySwipeOverlapSpec.incomingAlpha(180), 0f);
	}

	@Test public void handednessPreservesReleaseAndNeverOvershoots() {
		for (float release : new float[]{-45f, 45f}) {
			assertEquals(release, ReplySwipeOverlapSpec.offset(release, 0), 0f);
			float last = Math.abs(release);
			for (int t = 1; t < 160; t++) {
				float current = ReplySwipeOverlapSpec.offset(release, t);
				assertEquals(Math.signum(release), Math.signum(current), 0f);
				assertTrue(Math.abs(current) < last);
				last = Math.abs(current);
			}
			assertEquals(0f, ReplySwipeOverlapSpec.offset(release, 180), 0f);
		}
	}
}
