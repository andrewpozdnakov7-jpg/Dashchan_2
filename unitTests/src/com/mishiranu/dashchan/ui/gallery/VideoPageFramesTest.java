package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;

public class VideoPageFramesTest {
	private static VideoPreparationIdentity key(String resource) {
		return new VideoPreparationIdentity(resource, "cache-" + resource, 100, 10, 0, true, true);
	}

	@Test public void bothDirectionsRetainTheSameFrameWithoutTakingTheDecoder() {
		VideoPageFrames<Object> frames = new VideoPageFrames<>();
		Object previous = new Object(), current = new Object(), next = new Object();
		frames.setWindow("a", Arrays.asList("b", "c"));
		frames.put(key("a"), previous); frames.put(key("b"), current); frames.put(key("c"), next);
		frames.setWindow("b", Collections.singletonList("c"));
		assertSame(previous, frames.get(key("a")));
		assertSame(next, frames.get(key("c")));
		assertSame(current, frames.get(key("b")));
		assertSame(previous, frames.get(key("a")));
		assertEquals(3, frames.size());
	}

	@Test public void fileBookmarkAndDecoderPolicyChangesCannotReturnAnOldFrame() {
		VideoPageFrames<Object> frames = new VideoPageFrames<>();
		frames.setWindow("a", Collections.emptyList());
		Object image = new Object(); frames.put(key("a"), image);
		assertNull(frames.get(new VideoPreparationIdentity("a", "other", 100, 10, 0, true, true)));
		assertNull(frames.get(new VideoPreparationIdentity("a", "cache-a", 101, 10, 0, true, true)));
		assertNull(frames.get(new VideoPreparationIdentity("a", "cache-a", 100, 11, 0, true, true)));
		assertNull(frames.get(new VideoPreparationIdentity("a", "cache-a", 100, 10, 300, true, true)));
		assertNull(frames.get(new VideoPreparationIdentity("a", "cache-a", 100, 10, 0, false, true)));
		assertNull(frames.get(new VideoPreparationIdentity("a", "cache-a", 100, 10, 0, true, false)));
		assertSame(image, frames.get(key("a")));
	}

	@Test public void evictionReloadAndCloseDropOnlyOwnerReferences() {
		VideoPageFrames<Object> frames = new VideoPageFrames<>();
		Object borrowed = new Object();
		frames.setWindow("0", Collections.emptyList()); frames.put(key("0"), borrowed);
		for (int i = 1; i <= 21; i++) {
			frames.setWindow(Integer.toString(i), Collections.emptyList());
			frames.put(key(Integer.toString(i)), new Object());
		}
		assertNull(frames.get(key("0"))); assertNotNull(borrowed);
		assertEquals(21, frames.size()); // Twenty viewed frames plus the current frame.
		for (int i = 1; i <= 21; i++) assertNotNull(frames.get(key(Integer.toString(i))));
		frames.remove("1"); assertNull(frames.get(key("1")));
		frames.clear(); assertEquals(0, frames.size()); assertNotNull(borrowed);
		frames.put(key("21"), new Object()); assertEquals(0, frames.size());
	}

	@Test public void previousFrameBecomesValidAfterBookmarkMovesToCurrentVideoWithoutNewPublication() {
		VideoPageFrames<Object> frames = new VideoPageFrames<>();
		Object previous = new Object(), current = new Object();
		frames.setWindow("a", Collections.singletonList("b"));
		frames.put(key("a"), previous); frames.put(key("b"), current);
		frames.setWindow("b", Collections.emptyList());
		// Soft interruption temporarily saves the outgoing A position before B starts.
		assertNull(frames.get(new VideoPreparationIdentity("a", "cache-a", 100, 10, 850, true, true)));
		// B now owns the only bookmark: A will restart at zero on a reverse swipe.
		// No put/new preparation is needed; the neighbour must be rebound at this boundary.
		assertSame(previous, frames.get(key("a")));
		assertSame(current, frames.get(key("b")));
		assertEquals(2, frames.size());
	}

	@Test public void upcomingFramesDoNotDisplaceTwentyViewedFramesAndWindowCanShrink() {
		VideoPageFrames<Object> frames = new VideoPageFrames<>();
		for (int i = 0; i <= 20; i++) {
			frames.setWindow("viewed-" + i, Collections.emptyList());
			frames.put(key("viewed-" + i), new Object());
		}
		frames.setWindow("viewed-20", Arrays.asList("next-1", "next-2", "next-3", "next-4", "next-5"));
		for (int i = 1; i <= 5; i++) frames.put(key("next-" + i), new Object());
		assertEquals(26, frames.size());
		for (int i = 0; i <= 20; i++) assertNotNull(frames.get(key("viewed-" + i)));
		frames.setWindow("viewed-20", Arrays.asList("next-1", "next-2"));
		assertEquals(23, frames.size());
		assertNull(frames.get(key("next-3"))); assertNull(frames.get(key("next-5")));
		frames.setWindow("viewed-20", Collections.emptyList()); assertEquals(21, frames.size());
	}

	@Test public void viewingAgainRefreshesHistoryButUnviewedSpeculationDoesNotBecomeHistory() {
		VideoPageFrames<Object> frames = new VideoPageFrames<>();
		for (int i = 0; i <= 20; i++) {
			frames.setWindow("v" + i, Collections.emptyList()); frames.put(key("v" + i), new Object());
		}
		frames.setWindow("v0", Arrays.asList("never-viewed", "never-viewed"));
		frames.put(key("never-viewed"), new Object());
		frames.setWindow("v21", Collections.emptyList()); frames.put(key("v21"), new Object());
		assertNotNull(frames.get(key("v0"))); assertNull(frames.get(key("v1")));
		assertNull(frames.get(key("never-viewed"))); assertEquals(21, frames.size());
	}
}
