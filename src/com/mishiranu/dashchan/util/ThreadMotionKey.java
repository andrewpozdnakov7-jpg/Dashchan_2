package com.mishiranu.dashchan.util;

import java.util.Objects;

/** Presentation identity: position alone is unsafe after sorting or recycling a catalog. */
public final class ThreadMotionKey {
	public final String chanName, boardName, threadNumber;

	public ThreadMotionKey(String chanName, String boardName, String threadNumber) {
		this.chanName = chanName;
		this.boardName = boardName;
		this.threadNumber = threadNumber;
	}

	@Override public boolean equals(Object other) {
		if (!(other instanceof ThreadMotionKey)) return false;
		ThreadMotionKey key = (ThreadMotionKey) other;
		return Objects.equals(chanName, key.chanName) && Objects.equals(boardName, key.boardName) &&
				Objects.equals(threadNumber, key.threadNumber);
	}

	@Override public int hashCode() { return Objects.hash(chanName, boardName, threadNumber); }
}
