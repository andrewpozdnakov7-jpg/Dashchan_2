package com.mishiranu.dashchan.content.context;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContextSnapshotGuardTest {
	@Test public void unchangedSnapshotCanBeUsed() {
		assertTrue(ContextSnapshotGuard.isCurrent("revision-a", 7, new String("revision-a"), 7));
	}
	@Test public void editedPostOrLinksInvalidateSnapshot() {
		assertFalse(ContextSnapshotGuard.isCurrent("revision-a", 7, "revision-b", 7));
	}
	@Test public void CacheClearInvalidatesEvenEqualRevision() {
		assertFalse(ContextSnapshotGuard.isCurrent("revision-a", 7, "revision-a", 8));
	}
}
