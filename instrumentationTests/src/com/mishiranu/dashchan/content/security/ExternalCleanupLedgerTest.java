package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

/** Metadata-only fixtures. NEVER calls DownloadTreeCleanup or PrivacyReset.run(). */
public class ExternalCleanupLedgerTest {
	@Test public void localOnlyResetDoesNotForgetSkippedTree() throws Exception {
		JSONObject state = new JSONObject();
		ExternalCleanupLedger.skipped(state, "tree-A");
		ExternalCleanupLedger.cleared(state, null);
		assertTrue(ExternalCleanupLedger.hasIncomplete(state));
		assertEquals(ExternalCleanupLedger.identity("tree-A"), state.getJSONArray("external_cleanup_target_hashes").getString(0));
	}
	@Test public void successForAnotherTreeDoesNotCloseTheOldWarning() throws Exception {
		JSONObject state = new JSONObject();
		ExternalCleanupLedger.skipped(state, "tree-A");
		ExternalCleanupLedger.cleared(state, "tree-B");
		assertTrue(ExternalCleanupLedger.hasIncomplete(state));
		ExternalCleanupLedger.cleared(state, "tree-A");
		assertFalse(ExternalCleanupLedger.hasIncomplete(state));
	}
	@Test public void eachTargetMustBeClearedSeparatelyAndDuplicateSkipIsIdempotent() throws Exception {
		JSONObject state = new JSONObject();
		ExternalCleanupLedger.skipped(state, "tree-A"); ExternalCleanupLedger.skipped(state, "tree-A");
		ExternalCleanupLedger.skipped(state, "tree-B");
		assertEquals(2, state.getJSONArray("external_cleanup_target_hashes").length());
		ExternalCleanupLedger.cleared(state, "tree-A"); assertTrue(ExternalCleanupLedger.hasIncomplete(state));
		ExternalCleanupLedger.cleared(state, "tree-B"); assertFalse(ExternalCleanupLedger.hasIncomplete(state));
	}
	@Test public void oldBooleanWithoutATargetCannotBeClosedByAnyLaterUnrelatedSuccess() throws Exception {
		JSONObject state = new JSONObject().put("external_cleanup_incomplete", true);
		ExternalCleanupLedger.cleared(state, null);
		ExternalCleanupLedger.cleared(state, "tree-B");
		assertTrue(ExternalCleanupLedger.hasIncomplete(state));
		assertTrue(state.getBoolean("external_cleanup_unknown"));
	}
	@Test public void receiptClassificationDoesNotInventAResetFromMissingState() throws Exception {
		JSONObject state = new JSONObject();
		assertEquals(PrivacyReset.RecoveryState.NORMAL_START, PrivacyReset.recoveryState(state, 3));
		state.put("empty_launch", "synthetic").put("profile_generation", 2)
				.put("empty_launch_generation", 2).put("empty_launch_boot", 3);
		assertEquals(PrivacyReset.RecoveryState.COMPLETED_RECEIPT, PrivacyReset.recoveryState(state, 3));
		assertEquals(PrivacyReset.RecoveryState.NORMAL_START, PrivacyReset.recoveryState(state, 4));
		state.put("reset_pending", true);
		assertEquals(PrivacyReset.RecoveryState.RESET_PENDING, PrivacyReset.recoveryState(state, 3));
	}
}
