package com.mishiranu.dashchan.media;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoFrameReadinessTest {
	private static float[] emptyTransform() {
		float[] matrix = new float[16];
		matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1f;
		return matrix;
	}

	@Test public void unchangedEmptyLayerIsNotVideo() {
		VideoFrameReadiness gate = new VideoFrameReadiness(0L, emptyTransform(), false);
		assertFalse(gate.hasBuffer(0L, emptyTransform()));
		assertFalse(gate.hasBuffer(0L, emptyTransform()));
	}

	@Test public void acquiredTimestampIdentifiesBuffer() {
		VideoFrameReadiness gate = new VideoFrameReadiness(0L, emptyTransform(), false);
		assertTrue(gate.hasBuffer(123L, emptyTransform()));
	}

	@Test public void zeroTimestampBufferUsesSamplingTransform() {
		VideoFrameReadiness gate = new VideoFrameReadiness(0L, emptyTransform(), false);
		float[] bufferTransform = emptyTransform();
		bufferTransform[5] = -1f;
		bufferTransform[13] = 1f;
		assertTrue(gate.hasBuffer(0L, bufferTransform));
	}

	@Test public void timestampDoesNotNeedToIncrease() {
		VideoFrameReadiness gate = new VideoFrameReadiness(123L, emptyTransform(), false);
		assertTrue(gate.hasBuffer(0L, emptyTransform()));
		assertTrue(gate.hasBuffer(-10L, emptyTransform()));
	}

	@Test public void pausedTransferKeepsItsPreviouslyRenderedBuffer() {
		VideoFrameReadiness gate = new VideoFrameReadiness(0L, emptyTransform(), true);
		assertTrue(gate.hasBuffer(0L, emptyTransform()));
	}

	@Test public void baselineIsNotMutatedBySubsequentReads() {
		float[] transform = emptyTransform();
		VideoFrameReadiness gate = new VideoFrameReadiness(0L, transform, false);
		transform[5] = -1f;
		assertTrue(gate.hasBuffer(0L, transform));
		assertFalse(gate.hasBuffer(0L, emptyTransform()));
	}
}
