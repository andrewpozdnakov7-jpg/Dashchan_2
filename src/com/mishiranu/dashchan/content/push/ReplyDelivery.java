package com.mishiranu.dashchan.content.push;

/** Ordering shared by immediate delivery and durable retries. Notification state belongs to the store. */
final class ReplyDelivery {
	interface Steps {
		boolean persist();
		boolean deliverPending();
		void complete();
		void markHandled();
	}

	private ReplyDelivery() {}

	static boolean finish(Steps steps) {
		if (!steps.persist() || !steps.deliverPending()) return false;
		steps.complete();
		if (!steps.persist()) return false;
		steps.markHandled();
		return true;
	}
}
