package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class ReplyNotificationDeliveryTest {
	private static class Sink implements ReplyNotificationDelivery.Sink<String> {
		final Set<String> children = new HashSet<>();
		boolean summary, failChild, failSummary;
		int childCalls, summaryCalls, alerts;
		List<String> summaryReplies;
		@Override public Set<String> activeChildTags() { return new HashSet<>(children); }
		@Override public boolean hasSummary() { return summary; }
		@Override public String tag(String reply) { return reply; }
		@Override public void postChild(String reply) {
			childCalls++;
			if (failChild) throw new IllegalStateException("child");
			children.add(reply);
		}
		@Override public void postSummary(List<String> replies, boolean alert) {
			summaryCalls++;
			if (failSummary) throw new IllegalStateException("summary");
			summary = true;
			summaryReplies = replies;
			if (alert) alerts++;
		}
	}

	@Test public void failedSummaryIsRepairedOnRetryWithoutDuplicateChild() {
		Sink sink = new Sink();
		sink.failSummary = true;
		try {
			ReplyNotificationDelivery.deliver(Collections.singletonList("reply"), true, sink);
			fail("Must retry failed summary");
		} catch (IllegalStateException expected) { assertEquals("summary", expected.getMessage()); }
		assertEquals(1, sink.childCalls);
		assertFalse(sink.summary);
		sink.failSummary = false;
		ReplyNotificationDelivery.deliver(Collections.singletonList("reply"), true, sink);
		assertEquals(1, sink.childCalls);
		assertEquals(2, sink.summaryCalls);
		assertTrue(sink.summary);
		assertEquals(0, sink.alerts);
	}

	@Test public void completeGroupRetryMakesNoNotificationCalls() {
		Sink sink = new Sink();
		ReplyNotificationDelivery.deliver(Collections.singletonList("reply"), true, sink);
		ReplyNotificationDelivery.deliver(Collections.singletonList("reply"), true, sink);
		assertEquals(1, sink.childCalls);
		assertEquals(1, sink.summaryCalls);
		assertEquals(1, sink.alerts);
	}

	@Test public void childFailureDoesNotPublishSummary() {
		Sink sink = new Sink();
		sink.failChild = true;
		try {
			ReplyNotificationDelivery.deliver(Collections.singletonList("reply"), true, sink);
			fail("Must propagate failure");
		} catch (IllegalStateException expected) { }
		assertEquals(0, sink.summaryCalls);
		sink.failChild = false;
		ReplyNotificationDelivery.deliver(Collections.singletonList("reply"), true, sink);
		assertTrue(sink.summary);
	}

	@Test public void survivingChildAfterProcessDeathGetsQuietSummary() {
		Sink sink = new Sink();
		sink.children.add("reply");
		ReplyNotificationDelivery.deliver(Collections.singletonList("reply"), true, sink);
		assertEquals(0, sink.childCalls);
		assertEquals(1, sink.summaryCalls);
		assertEquals(0, sink.alerts);
	}

	@Test public void mixedOldAndNewRepliesRetainSummaryContents() {
		Sink sink = new Sink();
		sink.children.add("old");
		List<String> replies = Arrays.asList("old", "new");
		ReplyNotificationDelivery.deliver(replies, true, sink);
		assertEquals(1, sink.childCalls);
		assertEquals(replies, sink.summaryReplies);
		assertEquals(1, sink.alerts);
	}

	@Test public void repeatedSummaryFailureKeepsPropagating() {
		Sink sink = new Sink();
		sink.failSummary = true;
		for (int i = 0; i < 2; i++) {
			try {
				ReplyNotificationDelivery.deliver(Collections.singletonList("reply"), true, sink);
				fail("Incomplete group must not be acknowledged");
			} catch (IllegalStateException expected) { }
		}
		assertEquals(1, sink.childCalls);
		assertEquals(2, sink.summaryCalls);
	}

	@Test public void noPendingRepliesDoesNotResurrectNotifications() {
		Sink sink = new Sink();
		ReplyNotificationDelivery.deliver(Collections.emptyList(), true, sink);
		assertEquals(0, sink.childCalls);
		assertEquals(0, sink.summaryCalls);
	}

	@Test public void ordinaryWatcherKeepsNormalDelivery() {
		Sink sink = new Sink();
		sink.children.add("reply");
		sink.summary = true;
		ReplyNotificationDelivery.deliver(Collections.singletonList("reply"), false, sink);
		assertEquals(1, sink.childCalls);
		assertEquals(1, sink.summaryCalls);
		assertEquals(1, sink.alerts);
	}
}
