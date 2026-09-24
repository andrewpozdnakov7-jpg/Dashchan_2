package com.mishiranu.dashchan.content;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Two-stage notification delivery: an existing child does not prove the group is complete. */
final class ReplyNotificationDelivery {
	interface Sink<T> {
		Set<String> activeChildTags();
		boolean hasSummary();
		String tag(T reply);
		void postChild(T reply);
		void postSummary(List<T> replies, boolean alert);
	}

	static <T> void deliver(List<T> replies, boolean deduplicate, Sink<T> sink) {
		if (replies.isEmpty()) return;
		Set<String> active = deduplicate ? sink.activeChildTags() : Collections.emptySet();
		boolean posted = false;
		for (T reply : replies) {
			if (!active.contains(sink.tag(reply))) {
				sink.postChild(reply);
				posted = true;
			}
		}
		// If summary failed (or the process died after child notify), repair it quietly.
		// A fully published group needs no notify calls and must not alert twice.
		if (posted || !sink.hasSummary()) sink.postSummary(replies, posted);
	}
}
