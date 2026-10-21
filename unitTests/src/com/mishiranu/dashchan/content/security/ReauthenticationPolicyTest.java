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
	@Test public void tenMinutesOfActiveReadingDoNotConsumeTheNextThreeMinuteAbsence() {
		long proof = 1_000, background = proof + 10 * 60_000L;
		assertFalse(ReauthenticationPolicy.canRestore(proof, 3, 5, 5, background));
		// Renewal additionally requires a verified MAC and the provider's background grant.
		assertTrue(ReauthenticationPolicy.canStartBackgroundInterval(proof, 3, 5, 5, background));
		assertTrue(ReauthenticationPolicy.canRestore(background, 3, 5, 5, background + 150_000));
		assertFalse(ReauthenticationPolicy.canRestore(background, 3, 5, 5, background + 180_000));
	}
	@Test public void returningAfterTwoAndAHalfMinutesAllowsANewFullIntervalOnNextDeparture() {
		long firstDeparture = 1_000, firstReturn = firstDeparture + 150_000;
		assertTrue(ReauthenticationPolicy.canRestore(firstDeparture, 3, 7, 7, firstReturn));
		long nextDeparture = firstReturn + 600_000;
		assertTrue(ReauthenticationPolicy.canStartBackgroundInterval(firstDeparture, 3, 7, 7, nextDeparture));
		assertTrue(ReauthenticationPolicy.canRestore(nextDeparture, 3, 7, 7, nextDeparture + 179_999));
		assertFalse(ReauthenticationPolicy.canRestore(nextDeparture, 3, 7, 7, nextDeparture + 180_000));
		// Merely opening/querying while STILL in the background cannot use this renewal rule.
		assertFalse(ReauthenticationPolicy.canRestore(firstDeparture, 3, 7, 7, nextDeparture));
	}
	@Test public void backgroundRenewalNeverAcceptsImmediateUnknownBootOrClockRollback() {
		assertFalse(ReauthenticationPolicy.canStartBackgroundInterval(100, 0, 2, 2, 200));
		assertFalse(ReauthenticationPolicy.canStartBackgroundInterval(100, 4, 2, 2, 200));
		assertFalse(ReauthenticationPolicy.canStartBackgroundInterval(100, 3, 2, 3, 200));
		assertFalse(ReauthenticationPolicy.canStartBackgroundInterval(100, 3, -1, -1, 200));
		assertFalse(ReauthenticationPolicy.canStartBackgroundInterval(100, 3, 2, 2, 99));
		assertFalse(ReauthenticationPolicy.canStartBackgroundInterval(-1, 3, 2, 2, 200));
	}
}
