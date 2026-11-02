package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import org.junit.Test;

/** Content visibility, never an authentication grant. ADDED / NOT RUN. */
public class ReplyNotificationPrivacyTest {
	@Test public void defaultHidesOnlyProtectedBackgroundAndExplicitChoiceAllowsContents() {
		assertFalse(ReplyNotificationPrivacy.hide(false, true, false, false));
		assertFalse(ReplyNotificationPrivacy.hide(true, true, true, false));
		assertTrue(ReplyNotificationPrivacy.hide(true, true, false, false));
		assertFalse(ReplyNotificationPrivacy.hide(true, false, false, false));
	}
	@Test public void pendingCleanupAlwaysConcealsEvenAnExplicitOptOut() {
		for (boolean enabled : new boolean[]{false, true}) for (boolean hidden : new boolean[]{false, true}) {
			assertTrue(ReplyNotificationPrivacy.hide(enabled, hidden, true, true));
			assertTrue(ReplyNotificationPrivacy.hide(enabled, hidden, false, true));
		}
	}
}
