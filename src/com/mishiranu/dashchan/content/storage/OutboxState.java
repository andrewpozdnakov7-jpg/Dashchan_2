package com.mishiranu.dashchan.content.storage;

/** Persisted names are schema values. Recovery never turns uncertainty into an automatic retry. */
public enum OutboxState {
	PREPARING, READY, SENDING, WAITING, SENT, NEEDS_CAPTCHA, NEEDS_LOGIN, FAILED, INTERRUPTED, UNKNOWN_RESULT;

	public boolean isActive() {
		return this == PREPARING || this == READY || this == SENDING || this == WAITING;
	}

	public OutboxState afterProcessDeath() {
		return this == SENDING ? UNKNOWN_RESULT : isActive() ? INTERRUPTED : this;
	}

	public OutboxState afterCancellation() {
		return this == SENDING ? UNKNOWN_RESULT : isActive() ? INTERRUPTED : this;
	}
}
