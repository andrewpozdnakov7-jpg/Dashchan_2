package com.mishiranu.dashchan.util;

/** One gesture, one data generation, one composite identity. No positions or Android state. */
public final class ThreadSwipeIdentity {
	public final ThreadMotionKey key;
	private long generation;
	private boolean cancelled, consumed;

	public ThreadSwipeIdentity(ThreadMotionKey key, long generation) {
		this.key = key; this.generation = generation;
	}
	public boolean matches(ThreadMotionKey current, long generation) {
		return !cancelled && !consumed && this.generation == generation && key.equals(current);
	}
	public boolean consume(ThreadMotionKey current, long generation) {
		if (!matches(current, generation)) return false;
		consumed = true; return true;
	}
	/** Only a synchronous, already validated hide of another thread may advance a live gesture. */
	public void advanceAfterRelatedHide(long generation) {
		if (!cancelled && !consumed) this.generation = generation;
	}
	public void cancel() { cancelled = true; }
}
