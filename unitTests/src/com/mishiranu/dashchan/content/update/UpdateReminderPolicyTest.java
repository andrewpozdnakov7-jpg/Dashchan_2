package com.mishiranu.dashchan.content.update;

import static org.junit.Assert.*;
import org.junit.Test;

public class UpdateReminderPolicyTest {
	@Test public void postponementUsesThreeThenSixThenFifteenAdditionalLaunches() {
		long launch = 10;
		for (int i = 0; i < 8; i++) {
			int delay = i == 0 ? 3 : i == 1 ? 6 : 15;
			long next = UpdateReminderPolicy.nextLaunch(launch, i);
			assertEquals(launch + delay, next);
			for (long before = launch; before < next; before++) assertFalse(UpdateReminderPolicy.due(before, next));
			assertTrue(UpdateReminderPolicy.due(next, next));
			launch = next;
		}
	}
	@Test public void resumeRotationAndUnlockInTheSameProcessDoNotCount() {
		long launches = UpdateReminderPolicy.countLaunch(0, "previous-process", "current-process");
		assertEquals(1, launches);
		for (int i = 0; i < 100; i++) launches = UpdateReminderPolicy.countLaunch(launches, "current-process", "current-process");
		assertEquals(1, launches);
		assertEquals(2, UpdateReminderPolicy.countLaunch(launches, "current-process", "next-process"));
	}
	@Test public void countersCannotOverflowAndRemindImmediately() {
		assertEquals(Long.MAX_VALUE, UpdateReminderPolicy.nextLaunch(Long.MAX_VALUE - 1, 0));
		assertEquals(Long.MAX_VALUE, UpdateReminderPolicy.countLaunch(Long.MAX_VALUE, "a", "b"));
	}
}
