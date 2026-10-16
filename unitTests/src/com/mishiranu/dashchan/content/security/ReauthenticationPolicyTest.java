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
}
