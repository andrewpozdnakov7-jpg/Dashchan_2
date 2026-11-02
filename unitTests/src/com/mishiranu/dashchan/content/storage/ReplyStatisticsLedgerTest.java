package com.mishiranu.dashchan.content.storage;

import static org.junit.Assert.*;
import org.junit.Test;

public class ReplyStatisticsLedgerTest {
	@Test public void pushReloadAndSeveralReferencesCountOneReplyPost() {
		ReplyStatisticsLedger ledger = new ReplyStatisticsLedger(100);
		assertTrue(ledger.record("forum", "b", "100", "123", true));
		assertFalse(ledger.record("forum", "b", "100", "123", true));
		assertFalse(ledger.record("forum", "b", "100", "123", true));
		assertEquals(Long.valueOf(1), ledger.counts.get("forum"));
	}
	@Test public void forumBoardThreadAndSubPostNumbersRemainDistinct() {
		ReplyStatisticsLedger ledger = new ReplyStatisticsLedger(100);
		ledger.record("forum", "b", "100", "123", true);
		ledger.record("forum", "c", "100", "123", true);
		ledger.record("forum", "b", "101", "123", true);
		ledger.record("forum", "b", "100", "123.1", true);
		ledger.record("other", "b", "100", "123", true);
		assertEquals(Long.valueOf(4), ledger.counts.get("forum"));
		assertEquals(Long.valueOf(1), ledger.counts.get("other"));
	}
	@Test public void oldSavedRepliesAndKnownOwnRepliesAreSeededWithoutCounting() {
		ReplyStatisticsLedger ledger = new ReplyStatisticsLedger(100);
		assertTrue(ledger.record("forum", "b", "100", "123", false));
		assertFalse(ledger.record("forum", "b", "100", "123", true));
		assertTrue(ledger.counts.isEmpty());
		ledger.record("forum", "b", "100", "124", true);
		assertEquals(Long.valueOf(1), ledger.counts.get("forum"));
	}
	@Test public void resetKeepsDeduplicationAndStartsANewCounterPeriod() {
		ReplyStatisticsLedger ledger = new ReplyStatisticsLedger(100);
		ledger.record("forum", "b", "100", "123", true);
		ledger.reset(200);
		assertEquals(200, ledger.since); assertTrue(ledger.counts.isEmpty());
		assertFalse(ledger.record("forum", "b", "100", "123", true));
		ledger.record("forum", "b", "100", "124", true);
		assertEquals(Long.valueOf(1), ledger.counts.get("forum"));
	}
	@Test public void persistedSnapshotRestoresDeduplicationAndIsIndependentFromLiveMutations() {
		ReplyStatisticsLedger ledger = new ReplyStatisticsLedger(100);
		ledger.record("forum", "b", "100", "123", true);
		ReplyStatisticsLedger saved = ledger.copy();
		ledger.record("forum", "b", "100", "124", true); ledger.reset(200);
		ReplyStatisticsLedger restored = new ReplyStatisticsLedger(saved.since);
		restored.counts.putAll(saved.counts); restored.seen.addAll(saved.seen);
		assertEquals(100, restored.since);
		assertFalse(restored.record("forum", "b", "100", "123", true));
		assertTrue(restored.record("forum", "b", "100", "124", true));
		assertEquals(Long.valueOf(2), restored.counts.get("forum"));
		assertEquals(Long.valueOf(1), saved.counts.get("forum"));
		assertEquals(1, saved.seen.size());
	}
	@Test public void olderRepliesArrivingOutOfOrderAreNotLostToAHighWaterMark() {
		ReplyStatisticsLedger ledger = new ReplyStatisticsLedger(100);
		ledger.record("forum", "b", "100", "200", true);
		ledger.record("forum", "b", "100", "150", true);
		assertEquals(Long.valueOf(2), ledger.counts.get("forum"));
	}
	@Test public void identifiersAreLengthFramedAndNullBoardMatchesEmptyBoard() {
		ReplyStatisticsLedger ledger = new ReplyStatisticsLedger(100);
		assertTrue(ledger.record("a/b", "c", "100", "123", true));
		assertTrue(ledger.record("a", "b/c", "100", "123", true));
		assertTrue(ledger.record("forum", null, "100", "123", true));
		assertFalse(ledger.record("forum", "", "100", "123", true));
		for (String key : ledger.seen) assertTrue(key.matches("[0-9a-f]{64}"));
	}
}
