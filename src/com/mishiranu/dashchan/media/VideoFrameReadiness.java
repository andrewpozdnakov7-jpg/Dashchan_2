package com.mishiranu.dashchan.media;

import java.util.Arrays;

/** Compares a latched buffer with the untouched texture, not with another player's clock. */
final class VideoFrameReadiness {
	private final long emptyTimestamp;
	private final float[] emptyTransform;
	private final boolean retainedFrame;

	VideoFrameReadiness(long emptyTimestamp, float[] emptyTransform, boolean retainedFrame) {
		this.emptyTimestamp = emptyTimestamp;
		this.emptyTransform = emptyTransform.clone();
		this.retainedFrame = retainedFrame;
	}

	boolean hasBuffer(long timestamp, float[] transform) {
		// Zero and non-monotonic timestamps are legal. A zero-timestamp first buffer still
		// supplies the sampling transform (including the buffer's vertical flip/crop).
		// A transferred, already-rendered texture may retain exactly the same buffer.
		return retainedFrame || timestamp != emptyTimestamp || !Arrays.equals(transform, emptyTransform);
	}
}
