package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import org.junit.Test;

public class DrawerMotionSpecTest {
	@Test public void RealDrawerOffsetIsReversibleWithoutAnIndependentAnimationClock() {
		float lastReveal = 0f, lastDim = 0f;
		for (int i = 0; i <= 1000; i++) {
			float offset = i / 1000f;
			float reveal = DrawerMotionSpec.reveal(offset);
			float actualDim = offset * DrawerMotionSpec.scrimCoefficient(offset);
			assertTrue(reveal >= lastReveal); assertTrue(actualDim >= lastDim);
			assertTrue(reveal >= 0f && reveal <= 1f); assertTrue(actualDim <= 1f);
			lastReveal = reveal; lastDim = actualDim;
		}
		assertEquals(0f, DrawerMotionSpec.reveal(0f), 0f);
		assertEquals(1f, DrawerMotionSpec.reveal(1f), 0f);
		assertEquals(1f, DrawerMotionSpec.scrimCoefficient(1f), 0f);
		assertEquals(0f, DrawerMotionSpec.reveal(-1f), 0f);
		assertEquals(1f, DrawerMotionSpec.reveal(2f), 0f);
	}
}
