package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import org.junit.Test;

public class OwnPostRegistrationTest {
	@Test public void markingWithoutTrackingWritesOnlyIdentityNeverTextHistoryOrPush() {
		int[] effects = {0, 0};
		OwnPostRegistration.register(true, false, () -> effects[0]++, () -> effects[1]++);
		assertArrayEquals(new int[]{1, 0}, effects);
	}
	@Test public void trackingWithHiddenMarksStillRemembersIdentityAndTracksReplies() {
		StringBuilder order = new StringBuilder();
		OwnPostRegistration.register(false, true, () -> order.append("marker;"), () -> order.append("history-push;"));
		assertEquals("marker;history-push;", order.toString());
	}
	@Test public void bothEnabledRegisterEachEffectExactlyOnce() {
		int[] effects = {0, 0};
		OwnPostRegistration.register(true, true, () -> effects[0]++, () -> effects[1]++);
		assertArrayEquals(new int[]{1, 1}, effects);
	}
	@Test public void bothDisabledNeverWriteMarkersHistoryOrPush() {
		OwnPostRegistration.register(false, false, () -> fail("Unexpected marker"),
				() -> fail("Unexpected text history or push"));
	}
}
