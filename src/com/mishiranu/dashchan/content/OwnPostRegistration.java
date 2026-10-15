package com.mishiranu.dashchan.content;

/** Keep minimal own-post identity separate from text history, reply tracking and push registration. */
public final class OwnPostRegistration {
	private OwnPostRegistration() {}

	public static void register(boolean markPosts, boolean trackReplies, Runnable saveMarker, Runnable trackPost) {
		// Tracking also needs the identity when visual marks are hidden.
		if (markPosts || trackReplies) saveMarker.run();
		if (trackReplies) trackPost.run();
	}
}
