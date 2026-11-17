package com.mishiranu.dashchan.ui;

import org.junit.Test;
import java.util.Arrays;
import java.util.Set;
import static org.junit.Assert.*;

public class PageCleanupPolicyTest {
	private static PageCleanupPolicy.Entry entry(String thread, long visited, long order, boolean protect, boolean deleted) {
		return new PageCleanupPolicy.Entry(new PageCleanupPolicy.Key("chan", "b", thread), visited, order, protect, deleted);
	}

	@Test public void protectsCurrentAndFavoritesEvenWhenDeletedOrExpired() {
		PageCleanupPolicy.Entry current = entry("1", 1, 1, true, true);
		PageCleanupPolicy.Entry favorite = entry("2", 1, 2, true, true);
		PageCleanupPolicy.Entry deleted = entry("3", 90, 90, false, true);
		PageCleanupPolicy.Entry old = entry("4", 1, 1, false, false);
		PageCleanupPolicy.Entry fresh = entry("5", 99, 99, false, false);
		Set<PageCleanupPolicy.Key> remove = PageCleanupPolicy.select(Arrays.asList(current, favorite, deleted, old, fresh),
				1, 10, 100, true);
		assertEquals(2, remove.size());
		assertTrue(remove.contains(deleted.key)); assertTrue(remove.contains(old.key));
		assertFalse(remove.contains(current.key)); assertFalse(remove.contains(favorite.key));
		assertFalse(remove.contains(fresh.key));
	}

	@Test public void countUsesVisitOrderAndProtectedPagesAreAdditional() {
		PageCleanupPolicy.Entry first = entry("1", 100, 10, false, false);
		PageCleanupPolicy.Entry revisited = entry("2", 100, 30, false, false);
		PageCleanupPolicy.Entry middle = entry("3", 100, 20, false, false);
		PageCleanupPolicy.Entry protectedPage = entry("4", 1, 1, true, false);
		Set<PageCleanupPolicy.Key> remove = PageCleanupPolicy.select(Arrays.asList(revisited, protectedPage, first, middle),
				2, 0, 100, false);
		assertEquals(1, remove.size()); assertTrue(remove.contains(first.key));
	}

	@Test public void unknownAndFutureDatesDoNotExpireAndDisabledAgeDoesNotExpire() {
		PageCleanupPolicy.Entry unknown = entry("1", 0, 1, false, false);
		PageCleanupPolicy.Entry future = entry("2", 200, 2, false, false);
		PageCleanupPolicy.Entry old = entry("3", 1, 3, false, false);
		assertTrue(PageCleanupPolicy.select(Arrays.asList(unknown, future), 0, 10, 100, true).isEmpty());
		assertTrue(PageCleanupPolicy.select(Arrays.asList(old), 0, 10, 100, false).isEmpty());
		assertTrue(PageCleanupPolicy.select(Arrays.asList(old), 0, 0, 100, true).isEmpty());
	}

	@Test public void ageGateChecksAtMostEveryFifteenMinutesAndHandlesClockReset() {
		PageCleanupPolicy.AgeGate gate = new PageCleanupPolicy.AgeGate();
		assertTrue(gate.isDue(100)); gate.checked(100);
		assertFalse(gate.isDue(101));
		assertFalse(gate.isDue(100 + PageCleanupPolicy.AGE_CHECK_INTERVAL_MS - 1));
		assertTrue(gate.isDue(100 + PageCleanupPolicy.AGE_CHECK_INTERVAL_MS));
		assertTrue(gate.isDue(0));
		gate.reset(); assertTrue(gate.isDue(101));
	}
}
