package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import org.junit.Test;

public class BackPreviewTraceTest {
	@Test public void eventsBeforeRecordingRemainAvailableAcrossRecordingWindows() {
		BackPreviewTrace trace = new BackPreviewTrace("process-a", 8);
		trace.register("route-a", null, false, 10);
		trace.record("route-a", "stored", false, 20);
		String first = trace.describe("route-a", 100, 120);
		assertTrue(first.contains("snapshot_last_event=stored"));
		assertTrue(first.contains("snapshot_event_ms=-80"));
		assertTrue(first.contains("snapshot_stored_before_capture=true"));
		String second = trace.describe("route-a", 200, 220);
		assertTrue(second.contains("snapshot_stored_ms=-180"));
		assertTrue(second.contains("snapshot_state=available"));
		assertEquals(1, trace.size());
	}
	@Test public void removalBeforeRecordingKeepsItsReasonAndEarlierSuccessfulCapture() {
		BackPreviewTrace trace = new BackPreviewTrace("process-a", 8);
		trace.register("route-a", null, false, 10);
		trace.record("route-a", "stored", false, 20);
		trace.record("route-a", "cache_clear_low_memory", true, 40);
		// Later skipped work must not overwrite the last actual removal reason.
		trace.record("route-a", "capture_skipped_disabled", false, 50);
		String report = trace.describe("route-a", 100, 120);
		assertTrue(report.contains("snapshot_state=removed"));
		assertTrue(report.contains("snapshot_ever_stored=true"));
		assertTrue(report.contains("snapshot_last_removal=cache_clear_low_memory"));
		assertTrue(report.contains("snapshot_last_capture=capture_skipped_disabled"));
		assertTrue(report.contains("snapshot_removal_age_ms=80"));
	}
	@Test public void processRestartIsDifferentFromActivityRestorationAndLegacyState() {
		BackPreviewTrace trace = new BackPreviewTrace("new-process", 8);
		trace.register("restarted", "old-process", true, 10);
		trace.register("same-process", "new-process", true, 10);
		trace.register("legacy", null, true, 10);
		assertTrue(trace.describe("restarted", 20, 30).contains("process_restart_snapshot_not_retained"));
		assertTrue(trace.describe("same-process", 20, 30).contains("same_process_history_not_retained"));
		assertTrue(trace.describe("legacy", 20, 30).contains("legacy_state_without_snapshot_history"));
	}
	@Test public void restoringSameRouteDoesNotOverwriteItsKnownRemoval() {
		BackPreviewTrace trace = new BackPreviewTrace("process-a", 8);
		trace.register("route-a", null, false, 10);
		trace.record("route-a", "prune_unreachable", true, 20);
		trace.register("route-a", "process-a", true, 30);
		String report = trace.describe("route-a", 40, 50);
		assertTrue(report.contains("snapshot_origin=created_in_process"));
		assertTrue(report.contains("snapshot_last_event=prune_unreachable"));
	}
	@Test public void failedCaptureAndNeverCapturedRoutesAreDistinguished() {
		BackPreviewTrace trace = new BackPreviewTrace("process-a", 8);
		trace.register("never", null, false, 10);
		trace.register("failed", null, false, 10);
		trace.record("failed", "capture_failed", false, 20);
		assertTrue(trace.describe("never", 30, 40).contains("snapshot_state=not_captured"));
		assertTrue(trace.describe("failed", 30, 40).contains("snapshot_state=capture_failed"));
	}
	@Test public void capacityEvictsIncrementallyWithoutResettingOtherRouteHistories() {
		BackPreviewTrace trace = new BackPreviewTrace("process-a", 2);
		trace.register("a", null, false, 10);
		trace.register("b", null, false, 10);
		trace.record("b", "stored", false, 20);
		trace.register("c", null, false, 30);
		assertEquals(2, trace.size());
		assertEquals(1L, trace.evictions());
		assertTrue(trace.describe("b", 40, 50).contains("snapshot_last_event=stored"));
		assertTrue(trace.describe("a", 40, 50).contains("snapshot_last_event=history_not_retained"));
		assertEquals(2, trace.size());
	}
	@Test public void diagnosticDescriptionContainsNeitherRouteKeyNorProcessMarker() {
		BackPreviewTrace trace = new BackPreviewTrace("private-process-marker", 8);
		trace.register("private-route-key", "old-private-marker", true, 10);
		String report = trace.describe("private-route-key", 20, 30);
		assertFalse(report.contains("private-process-marker"));
		assertFalse(report.contains("private-route-key"));
		assertFalse(report.contains("old-private-marker"));
		assertEquals("snapshot_history=no_destination", trace.describe(null, 20, 30));
	}
}
