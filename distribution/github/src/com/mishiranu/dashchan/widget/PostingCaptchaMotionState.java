package com.mishiranu.dashchan.widget;

/** Presentation revisions only: no captcha payloads, networking, validation or posting. */
final class PostingCaptchaMotionState {
	enum State {READY, LOADING, DONE, ERROR, LEGACY}
	static final class Change {
		final long revision;
		final boolean changed, animate, success;
		Change(long revision, boolean changed, boolean animate, boolean success) {
			this.revision = revision; this.changed = changed; this.animate = animate; this.success = success;
		}
	}
	private State state;
	private long revision;
	private boolean closed;
	Change bind(State next, boolean restoring, boolean motionAllowed) {
		boolean changed = state != next;
		boolean animate = !closed && state != null && changed && !restoring && motionAllowed;
		if (changed || restoring) revision++;
		state = next;
		return new Change(revision, changed, animate, animate && next == State.DONE);
	}
	boolean owns(long token) { return !closed && token == revision; }
	void close() { closed = true; revision++; }
	static State resolve(boolean loading, boolean error, boolean answerSaved, boolean compactEntry) {
		return loading ? State.LOADING : error ? State.ERROR : answerSaved ? State.DONE
				: compactEntry ? State.READY : State.LEGACY;
	}
}
