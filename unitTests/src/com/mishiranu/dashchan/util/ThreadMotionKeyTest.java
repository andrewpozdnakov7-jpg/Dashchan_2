package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import java.util.HashMap;
import org.junit.Test;

public class ThreadMotionKeyTest {
	@Test public void catalogReorderingDoesNotChangeThreadIdentity() {
		ThreadMotionKey before = new ThreadMotionKey("a", "b", "123");
		ThreadMotionKey rebound = new ThreadMotionKey("a", "b", "123");
		HashMap<ThreadMotionKey, String> anchors = new HashMap<>();
		anchors.put(before, "card");
		assertEquals("card", anchors.get(rebound));
	}

	@Test public void sameNumberOnAnotherBoardOrForumIsNotTheReturnCard() {
		ThreadMotionKey key = new ThreadMotionKey("a", "b", "123");
		assertNotEquals(key, new ThreadMotionKey("a", "c", "123"));
		assertNotEquals(key, new ThreadMotionKey("other", "b", "123"));
		assertNotEquals(key, new ThreadMotionKey("a", "b", "124"));
		assertNotEquals(key, null);
		assertNotEquals(key, "a/b/123");
	}
}
