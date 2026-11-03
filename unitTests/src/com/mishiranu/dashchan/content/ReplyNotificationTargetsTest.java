package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import org.junit.Test;

public class ReplyNotificationTargetsTest {
	@Test public void sourceChoiceIncludesEitherSourceButNeedsAnObservedTarget() {
		assertTrue(ReplyNotificationTargets.BOTH.allows(true, false, true, false));
		assertTrue(ReplyNotificationTargets.BOTH.allows(true, false, false, true));
		assertFalse(ReplyNotificationTargets.BOTH.allows(true, false, false, false));
		assertFalse(ReplyNotificationTargets.NONE.allows(true, false, true, true));
		assertFalse(ReplyNotificationTargets.FAVORITE.allows(true, false, false, true));
		assertFalse(ReplyNotificationTargets.TRACKED.allows(true, false, true, false));
	}

	@Test public void journalOffAndQuietHoursSilenceEverySource() {
		for (ReplyNotificationTargets targets : ReplyNotificationTargets.values()) {
			assertFalse(targets.allows(false, false, true, true));
			assertFalse(targets.allows(true, true, true, true));
		}
	}

	@Test public void restoreLastScopeOrUseBothWhenLegacyAlertsWereAllOff() {
		assertEquals(ReplyNotificationTargets.TRACKED, ReplyNotificationTargets.restoreScope("TRACKED"));
		assertEquals(ReplyNotificationTargets.FAVORITE, ReplyNotificationTargets.restoreScope("FAVORITE"));
		assertEquals(ReplyNotificationTargets.BOTH, ReplyNotificationTargets.restoreScope("BOTH"));
		assertEquals(ReplyNotificationTargets.BOTH, ReplyNotificationTargets.restoreScope(null));
		assertEquals(ReplyNotificationTargets.BOTH, ReplyNotificationTargets.restoreScope("NONE"));
		assertEquals(ReplyNotificationTargets.BOTH, ReplyNotificationTargets.restoreScope("unknown"));
	}

	@Test public void intersectingThreadHasTwoOrigins() {
		assertEquals(ReplyNotificationTargets.BOTH, ReplyNotificationTargets.of(true, true));
		assertEquals(ReplyNotificationTargets.FAVORITE, ReplyNotificationTargets.of(true, false));
		assertEquals(ReplyNotificationTargets.TRACKED, ReplyNotificationTargets.of(false, true));
		assertEquals(ReplyNotificationTargets.NONE, ReplyNotificationTargets.of(false, false));
	}
}
