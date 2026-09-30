package com.mishiranu.dashchan.ui.posting;

import org.junit.Test;
import static org.junit.Assert.*;

public class AttachmentResultGuardTest {
	@Test public void sameSourceAfterDraftRestoreIsAccepted() {
		assertTrue(AttachmentResultGuard.matches("hash-a", "a.png", new String("hash-a"), "a.png"));
	}
	@Test public void replacedOrReorderedAttachmentIsRejected() {
		assertFalse(AttachmentResultGuard.matches("hash-a", "a.png", "hash-b", "b.png"));
	}
	@Test public void renamedAttachmentIsRejected() {
		assertFalse(AttachmentResultGuard.matches("hash-a", "a.png", "hash-a", "b.png"));
	}
	@Test public void missingIdentityIsNotTreatedAsWildcard() {
		assertFalse(AttachmentResultGuard.matches(null, "a.png", "hash-a", "a.png"));
		assertFalse(AttachmentResultGuard.matches("", "a.png", "", "a.png"));
		assertFalse(AttachmentResultGuard.matches("hash-a", null, "hash-a", null));
	}
	@Test public void removedAttachmentIsRejected() {
		assertFalse(AttachmentResultGuard.matches("hash-a", "a.png", null, null));
	}
}
