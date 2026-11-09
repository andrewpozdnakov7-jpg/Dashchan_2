package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import org.junit.Test;

public class PostingAttachmentStateTest {
	@Test public void oneFileLimitChangesActionButKeepsTheKeyboardSlot() {
		PostingAttachmentState empty = new PostingAttachmentState(0, 1, false);
		PostingAttachmentState full = new PostingAttachmentState(1, 1, false);
		assertTrue(empty.canAdd()); assertFalse(empty.atLimit());
		assertTrue(empty.visible(true)); assertTrue(empty.visible(false));
		assertFalse(full.canAdd()); assertTrue(full.atLimit());
		assertTrue(full.visible(true)); assertFalse(full.visible(false));
	}

	@Test public void importIsNotAFullLimitAndDoesNotRemoveTheKeyboardSlot() {
		PostingAttachmentState busy = new PostingAttachmentState(1, 4, true);
		assertEquals(1, busy.count); assertTrue(busy.hasRoom()); assertFalse(busy.canAdd());
		assertFalse(busy.atLimit()); assertTrue(busy.visible(true)); assertFalse(busy.visible(false));
		assertTrue(new PostingAttachmentState(1, 4, false).canAdd());
	}

	@Test public void forbiddenBoardNeverShowsAButtonEvenWithOldDraftAttachments() {
		for (int count : new int[]{0, 1, 4}) {
			PostingAttachmentState forbidden = new PostingAttachmentState(count, 0, false);
			assertFalse(forbidden.visible(true)); assertFalse(forbidden.visible(false));
			assertFalse(forbidden.canAdd()); assertFalse(forbidden.atLimit());
		}
	}

	@Test public void currentBoardLimitRatherThanFileCountAloneDeterminesTheAction() {
		assertTrue(new PostingAttachmentState(1, 1, false).atLimit());
		assertFalse(new PostingAttachmentState(1, 4, false).atLimit());
		assertTrue(new PostingAttachmentState(3, 4, false).canAdd());
		assertTrue(new PostingAttachmentState(4, 4, false).atLimit());
		assertTrue(new PostingAttachmentState(3, 4, false).visible(false));
	}
}
