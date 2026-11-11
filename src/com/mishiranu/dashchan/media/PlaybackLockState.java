package com.mishiranu.dashchan.media;

/** Retain a blocked resume request without mistaking a lock-induced pause for the user's pause. */
public final class PlaybackLockState {
	private boolean held;
	public boolean request(boolean playing, boolean locked) {
		held = playing && locked;
		return playing && !locked;
	}
	public boolean beforePause(boolean actuallyPlaying) {
		boolean requested = actuallyPlaying || held; held = false; return requested;
	}
	public boolean hasPendingResume() { return held; }
	public void reset() { held = false; }
}
