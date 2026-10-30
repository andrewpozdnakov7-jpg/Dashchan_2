package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import org.junit.Test;

public class ScreenMotionSpecTest {
	@Test public void sectionWinsOverAFormDestinationAndCloseDoesNotPretendToOpenAForm() {
		assertEquals(ScreenMotionSpec.SECTION, ScreenMotionSpec.select(true, false, false, true));
		assertEquals(ScreenMotionSpec.COMPOSE_OPEN, ScreenMotionSpec.select(false, false, false, true));
		assertEquals(ScreenMotionSpec.COMPOSE_CLOSE, ScreenMotionSpec.select(false, true, true, false));
		assertEquals(ScreenMotionSpec.BACK, ScreenMotionSpec.select(false, true, false, true));
	}
	@Test public void oldAndNewTextNeverOverlapAndThereIsNoDeadInterval() {
		for (int i = 0; i <= 1000; i++) {
			float t = i / 1000f;
			float a = ScreenMotionSpec.outgoingAlpha(t), b = ScreenMotionSpec.incomingAlpha(t);
			assertTrue(a >= 0f && a <= 1f); assertTrue(b >= 0f && b <= 1f);
			assertFalse(a > 0f && b > 0f);
			if (Math.abs(t - .3f) > .00001f) assertTrue(a > 0f || b > 0f);
		}
		assertEquals(1f, ScreenMotionSpec.outgoingAlpha(0f), 0f);
		assertEquals(0f, ScreenMotionSpec.outgoingAlpha(1f), 0f);
		assertEquals(1f, ScreenMotionSpec.incomingAlpha(1f), 0f);
	}
	@Test public void forwardAndBackShareAnAxisAndMirrorForRtl() {
		assertEquals(ScreenMotionSpec.FORWARD.incomingX(2f, false), ScreenMotionSpec.BACK.outgoingX(2f, false), 0f);
		assertEquals(ScreenMotionSpec.FORWARD.outgoingX(2f, false), ScreenMotionSpec.BACK.incomingX(2f, false), 0f);
		for (ScreenMotionSpec spec : ScreenMotionSpec.values()) {
			assertEquals(-spec.incomingX(2f, false), spec.incomingX(2f, true), 0f);
			assertEquals(-spec.outgoingX(2f, false), spec.outgoingX(2f, true), 0f);
		}
	}
	@Test public void sectionAndFormDoNotSlideHorizontallyAndFormCloseTravelsDown() {
		assertEquals(0f, ScreenMotionSpec.SECTION.incomingX(1f, false), 0f);
		assertEquals(0f, ScreenMotionSpec.SECTION.outgoingY(1f), 0f);
		assertEquals(.96f, ScreenMotionSpec.SECTION.initialScale(), 0f);
		assertEquals(0f, ScreenMotionSpec.COMPOSE_OPEN.incomingX(1f, false), 0f);
		assertEquals(ScreenMotionSpec.COMPOSE_OPEN.incomingY(2f), ScreenMotionSpec.COMPOSE_CLOSE.outgoingY(2f), 0f);
		assertTrue(ScreenMotionSpec.COMPOSE_CLOSE.outgoingY(1f) > 0f);
	}
}
