package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import org.junit.Test;

public class DialogMotionSpecTest {
	@Test public void nestedPostTextNeverCrossfadesOverThePreviousPost() {
		for (int i = 0; i <= 1000; i++) {
			float clock = i / 1000f;
			float outgoing = DialogMotionSpec.outgoingAlpha(clock), incoming = DialogMotionSpec.incomingAlpha(clock);
			assertTrue(outgoing >= 0f && outgoing <= 1f); assertTrue(incoming >= 0f && incoming <= 1f);
			assertFalse(outgoing > 0f && incoming > 0f);
			if (Math.abs(clock - 0.3f) > 0.00001f) assertTrue(outgoing > 0f || incoming > 0f);
		}
		assertEquals(1f, DialogMotionSpec.outgoingAlpha(0f), 0f);
		assertEquals(1f, DialogMotionSpec.incomingAlpha(1f), 0f);
	}
}
