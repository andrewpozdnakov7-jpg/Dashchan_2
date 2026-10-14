package com.mishiranu.dashchan.widget;

/** Captcha presentation/request ownership only. There is deliberately no submission callback. */
final class PostingPrimaryState {
	enum Mode {SOLVE, LOADING, SEND}
	static final class Change {
		final Mode mode;
		final boolean changed, success, restoring, initial;
		Change(Mode mode, boolean changed, boolean success, boolean restoring, boolean initial) {
			this.mode = mode; this.changed = changed; this.success = success; this.restoring = restoring; this.initial = initial;
		}
	}
	private Mode mode;
	private boolean answered, requested, closed;
	Mode mode() { return mode; }
	static Mode resolve(boolean loading, boolean ready) { return loading ? Mode.LOADING : ready ? Mode.SEND : Mode.SOLVE; }
	Change bind(Mode next, boolean savedAnswer, boolean restoring) {
		boolean success = !closed && mode != null && !answered && savedAnswer && next == Mode.SEND && !restoring;
		Change change = new Change(next, mode != next, success, restoring, mode == null);
		mode = next; answered = savedAnswer;
		if (next == Mode.SEND) requested = false;
		return change;
	}
	boolean requestDialog() {
		if (closed || requested || mode == Mode.LOADING || mode == Mode.SEND) return false;
		requested = true; return true;
	}
	boolean consumeDialogRequest(boolean imageReady) {
		if (closed || !requested || !imageReady || mode == Mode.LOADING) return false;
		requested = false; return true;
	}
	void cancelRequest() { requested = false; }
	void close() { closed = true; requested = false; }
}
