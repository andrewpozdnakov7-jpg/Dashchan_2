package com.mishiranu.dashchan.content.security;

import java.util.function.BooleanSupplier;

/** One-shot visual continuation; never grants authentication. Owned by the UI thread. */
final class LockPresentationCompletion {
	private Runnable continuation;
	LockPresentationCompletion(Runnable continuation) { this.continuation = continuation; }
	void cancel() { continuation = null; }
	void finish(BooleanSupplier current) {
		Runnable next = continuation;
		continuation = null;
		if (next != null && current.getAsBoolean()) next.run();
	}
}
