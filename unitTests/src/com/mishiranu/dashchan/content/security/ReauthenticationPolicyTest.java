package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import org.junit.Test;

public class ReauthenticationPolicyTest {
	@Test public void onlyExplicitIntervalsAreAcceptedAndDefaultIsImmediate() {
		assertArrayEquals(new int[]{0, 3, 5, 10, 30, 60, 120, 300}, ReauthenticationPolicy.choices());
		for (int value : new int[]{-1, 1, 2, 4, 301, Integer.MAX_VALUE}) {
			assertEquals(0, ReauthenticationPolicy.normalize(value));
			assertEquals(0, ReauthenticationPolicy.indexOf(value));
		}
		int[] copy = ReauthenticationPolicy.choices(); copy[1] = 999;
		assertEquals(3, ReauthenticationPolicy.choices()[1]);
	}
	@Test public void grantExpiresExactlyAtTheBoundaryWithoutSlidingOnQueries() {
		for (int minutes : ReauthenticationPolicy.choices()) {
			ReauthenticationPolicy.Grant grant = new ReauthenticationPolicy.Grant(1_000, minutes);
			if (minutes > 0) {
				assertTrue(grant.valid(1_000));
				assertTrue(grant.valid(1_000 + minutes * 60_000L - 1));
			}
			assertFalse(grant.valid(1_000 + minutes * 60_000L));
			assertFalse(grant.valid(1_000 + minutes * 60_000L + 1));
		}
	}
	@Test public void invalidOrRestartedClockAndCorruptValuesNeverAuthorize() {
		assertFalse(new ReauthenticationPolicy.Grant(-1, 3).valid(0));
		assertFalse(new ReauthenticationPolicy.Grant(1_000, 3).valid(999));
		assertFalse(new ReauthenticationPolicy.Grant(1_000, Integer.MAX_VALUE).valid(1_000));
		assertFalse(new ReauthenticationPolicy.Grant(1_000, 0).valid(1_000));
		assertFalse(new ReauthenticationPolicy.Grant(1_000, 300).valid(Long.MAX_VALUE));
	}
	@Test public void savedIntervalSurvivesProcessLossButNeverBootChangesOrUnknownBoot() {
		long at = 123_000;
		assertTrue(ReauthenticationPolicy.canRestore(at, 5, 8, 8, at + 4 * 60_000));
		assertFalse(ReauthenticationPolicy.canRestore(at, 5, 8, 8, at + 5 * 60_000));
		// A reboot is rejected even if the new uptime happens to look within the old interval.
		assertFalse(ReauthenticationPolicy.canRestore(at, 5, 8, 9, at + 1));
		assertFalse(ReauthenticationPolicy.canRestore(at, 5, -1, -1, at + 1));
		assertFalse(ReauthenticationPolicy.canRestore(at, 5, 8, -1, at + 1));
		assertFalse(ReauthenticationPolicy.canRestore(at, 5, 8, 8, at - 1));
	}
	@Test public void restorationChecksDoNotMoveTheDeadlineForAnySupportedInterval() {
		for (int minutes : ReauthenticationPolicy.choices()) {
			long at = 5_000, end = at + minutes * 60_000L;
			if (minutes > 0) {
				assertTrue(ReauthenticationPolicy.canRestore(at, minutes, 3, 3, at));
				assertTrue(ReauthenticationPolicy.canRestore(at, minutes, 3, 3, end - 1));
			}
			assertFalse(ReauthenticationPolicy.canRestore(at, minutes, 3, 3, end));
			assertFalse(ReauthenticationPolicy.canRestore(at, minutes, 3, 3, Long.MAX_VALUE));
		}
	}
}
