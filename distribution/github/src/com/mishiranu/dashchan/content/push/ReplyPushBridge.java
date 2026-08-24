package com.mishiranu.dashchan.content.push;

import android.content.Context;

public final class ReplyPushBridge {
	private ReplyPushBridge() {}

	public static boolean isSupported() {
		// Keep the unfinished setting hidden until Firebase and the backend contract are connected.
		return false;
	}

	public static boolean isConfigured() {
		return false;
	}

	public static void setEnabled(Context context, boolean enabled, String installationId) {
		// Firebase and backend wiring will be added after the shared contract is available.
		// The final implementation must enqueue work and never block the caller or posting flow.
	}

	public static void registerWatch(Context context, String installationId, String chanName,
			String boardName, String threadNumber, String postNumber) {
		// Intentionally offline until the shared backend contract is available. The final operation
		// must be idempotent, asynchronous and independently retryable after a successful board post.
	}
}
