package com.mishiranu.dashchan.media;

/** Native buffering events may race seek events, but must never overwrite user intent. */
final class NetworkBufferingState {
	private long serial;
	private boolean buffering;

	boolean apply(long serial, boolean buffering) {
		if (serial <= this.serial) return false;
		this.serial = serial;
		this.buffering = buffering;
		return true;
	}

	boolean shouldPlay(boolean requestedPlaying) {
		return requestedPlaying && !buffering;
	}

	boolean shouldShowProgress(boolean requestedPlaying) {
		return requestedPlaying && buffering;
	}
}
