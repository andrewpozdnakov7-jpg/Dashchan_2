package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import org.junit.Test;

public class ThreadReturnPresentationTest {
	@Test public void differentTextLayoutsAreNeverVisibleTogether() {
		for (int i = 0; i <= 1000; i++) {
			float fraction = i / 1000f;
			float oldAlpha = ThreadReturnPresentation.messagesAlpha(fraction);
			float cardAlpha = ThreadReturnPresentation.cardAlpha(fraction);
			assertTrue(oldAlpha >= 0f && oldAlpha <= 1f);
			assertTrue(cardAlpha >= 0f && cardAlpha <= 1f);
			assertFalse("Two text layouts at " + fraction, oldAlpha > 0f && cardAlpha > 0f);
		}
	}

	@Test public void cardIsCompleteBeforeTheContainerArrivesAndMessagesDoNotTravelWithIt() {
		assertEquals(1f, ThreadReturnPresentation.messagesAlpha(0f), 0f);
		assertEquals(0f, ThreadReturnPresentation.cardAlpha(0f), 0f);
		assertEquals(0f, ThreadReturnPresentation.messagesAlpha(.3f), 0f);
		assertEquals(1f, ThreadReturnPresentation.cardAlpha(.6f), 0f);
		assertEquals(0f, ThreadReturnPresentation.messagesAlpha(1f), 0f);
		assertEquals(1f, ThreadReturnPresentation.cardAlpha(1f), 0f);
	}
}
