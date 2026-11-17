package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import org.junit.Test;

public class LocalArchiveUpdateLeaseTest {
	@Test public void sameThreadStaysOwnedUntilExplicitCompletionAndOldCloseCannotUnlockNewOwner() {
		LocalArchiveUpdateLease first = LocalArchiveUpdateLease.acquire("board/thread");
		assertNotNull(first);
		try { assertNull(LocalArchiveUpdateLease.acquire("board/thread")); }
		finally { first.close(); }
		try (LocalArchiveUpdateLease next = LocalArchiveUpdateLease.acquire("board/thread")) {
			assertNotNull(next);
			first.close();
			assertNull(LocalArchiveUpdateLease.acquire("board/thread"));
		}
		try (LocalArchiveUpdateLease last = LocalArchiveUpdateLease.acquire("board/thread")) { assertNotNull(last); }
	}

	@Test public void differentThreadsCannotReserveCaseVariantsOfOneSnapshotName() {
		try (LocalArchiveUpdateLease first = LocalArchiveUpdateLease.acquire("a");
				LocalArchiveUpdateLease second = LocalArchiveUpdateLease.acquire("b")) {
			assertTrue(first.reserveName("Thread"));
			assertFalse(second.reserveName("thread"));
			assertTrue(second.reserveName("thread_1"));
		}
		try (LocalArchiveUpdateLease after = LocalArchiveUpdateLease.acquire("c")) {
			assertTrue(after.reserveName("THREAD"));
		}
	}
}
