package com.mishiranu.dashchan.media;

/** Completion belongs to a committed native timeline and a Java seek request. */
final class PlaybackCompletion {
	final long generation;
	final long requestId;

	PlaybackCompletion(long generation, long requestId) {
		this.generation = generation;
		this.requestId = requestId;
	}

	boolean isCurrent(long generation, long requestId, boolean seeking) {
		return !seeking && this.generation == generation && this.requestId == requestId;
	}
}
