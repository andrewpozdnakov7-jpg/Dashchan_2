package com.mishiranu.dashchan.ui.gallery;

import java.util.Objects;

/** Immutable preparation key; never log its resource/path fields. */
final class VideoPreparationIdentity {
	final String resource, path;
	final long length, modified, requestedPosition;
	final boolean seekAnyFrame, hardware;

	VideoPreparationIdentity(String resource, String path, long length, long modified,
			long requestedPosition, boolean seekAnyFrame, boolean hardware) {
		this.resource = resource; this.path = path; this.length = length; this.modified = modified;
		this.requestedPosition = requestedPosition; this.seekAnyFrame = seekAnyFrame; this.hardware = hardware;
	}

	boolean matches(VideoPreparationIdentity other) {
		return other != null && Objects.equals(resource, other.resource) && Objects.equals(path, other.path)
				&& length == other.length && modified == other.modified && requestedPosition == other.requestedPosition
				&& seekAnyFrame == other.seekAnyFrame && hardware == other.hardware;
	}

	static long startPosition(long requested, long duration) {
		return requested > 0 && requested < duration ? requested : 0L;
	}
}
