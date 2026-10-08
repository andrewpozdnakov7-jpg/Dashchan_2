package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class VideoScrubSessionTest {
	@Test public void continuousMovementDoesNotDebouncePreviewUntilFingerStops() {
		VideoScrubSession session = new VideoScrubSession();
		session.start();
		session.update(1000);
		assertEquals(1000, session.takePreview(0, false));
		for (int time = 10; time < 100; time += 10) {
			session.update(time * 100);
			assertEquals(-1, session.takePreview(time, false));
		}
		session.update(11000);
		assertEquals(11000, session.takePreview(100, false));
	}

	@Test public void busyDecoderRetainsOnlyLatestTarget() {
		VideoScrubSession session = new VideoScrubSession();
		session.start();
		session.update(1000);
		assertEquals(1000, session.takePreview(100, false));
		session.update(5000);
		assertEquals(-1, session.takePreview(200, true));
		session.update(9000);
		assertEquals(-1, session.takePreview(300, true));
		assertEquals(9000, session.takePreview(400, false));
		assertEquals(-1, session.takePreview(500, false));
	}

	@Test public void releaseAlwaysReturnsLastTargetDespiteIntervalOrBusyDecoder() {
		VideoScrubSession session = new VideoScrubSession();
		session.start();
		session.update(1000);
		assertEquals(1000, session.takePreview(100, false));
		session.update(9000);
		assertEquals(-1, session.takePreview(110, true));
		assertEquals(9000, session.finish());
		assertEquals(-1, session.finish());
		assertEquals(-1, session.takePreview(300, false));
	}

	@Test public void cancelledGestureCannotSeekNextVideo() {
		VideoScrubSession session = new VideoScrubSession();
		session.start();
		session.update(20000);
		session.cancel();
		session.update(30000);
		assertEquals(-1, session.finish());
		assertEquals(-1, session.takePreview(1000, false));
		session.start();
		assertEquals(-1, session.finish());
	}

	@Test public void repeatedTargetDoesNotRestartNativeSeek() {
		VideoScrubSession session = new VideoScrubSession();
		session.start();
		session.update(-10);
		assertEquals(0, session.takePreview(100, false));
		session.update(0);
		assertEquals(-1, session.takePreview(500, false));
		assertEquals(0, session.finish());
	}

	@Test public void completionIsDeferredAndConsumedOnce() {
		VideoScrubSession session = new VideoScrubSession();
		session.start();
		session.deferCompletion();
		org.junit.Assert.assertTrue(session.takeDeferredCompletion());
		org.junit.Assert.assertFalse(session.takeDeferredCompletion());
	}

	@Test public void newSeekInvalidatesDeferredCompletion() {
		VideoScrubSession session = new VideoScrubSession();
		session.start();
		session.deferCompletion();
		session.update(5000);
		session.clearDeferredCompletion();
		org.junit.Assert.assertFalse(session.takeDeferredCompletion());
		assertEquals(5000, session.finish());
	}

	@Test public void cancelledSessionCannotCompleteNextVideo() {
		VideoScrubSession session = new VideoScrubSession();
		session.start();
		session.deferCompletion();
		session.cancel();
		org.junit.Assert.assertFalse(session.takeDeferredCompletion());
		session.deferCompletion();
		org.junit.Assert.assertFalse(session.takeDeferredCompletion());
		session.start();
		org.junit.Assert.assertFalse(session.takeDeferredCompletion());
	}
}
