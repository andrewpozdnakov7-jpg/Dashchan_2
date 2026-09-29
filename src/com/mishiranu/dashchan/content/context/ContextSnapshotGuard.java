package com.mishiranu.dashchan.content.context;

import java.util.Objects;

/** A revision is meaningful only within the same cache lifetime. */
public final class ContextSnapshotGuard {
	private ContextSnapshotGuard() {}
	public static boolean isCurrent(Object expectedRevision, long expectedEpoch, Object revision, long epoch) {
		return expectedEpoch == epoch && Objects.equals(expectedRevision, revision);
	}
}
