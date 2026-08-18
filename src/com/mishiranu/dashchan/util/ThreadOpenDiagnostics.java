package com.mishiranu.dashchan.util;

import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;

/** Privacy-safe Logcat and Perfetto markers for the experimental fast thread opening mode. */
public final class ThreadOpenDiagnostics {
	public static final class Operation {
		private final int sessionId;
		private final int cookie;
		private final String name;
		private final long startRealtime;
		private boolean finished;

		private Operation(int sessionId, int cookie, String name) {
			this.sessionId = sessionId;
			this.cookie = cookie;
			this.name = name;
			startRealtime = SystemClock.elapsedRealtime();
		}
	}

	private static final String TAG = "ThreadOpenPerf";
	private static final String TRACE_PREFIX = "ThreadOpen/";
	private static final AtomicInteger NEXT_COOKIE = new AtomicInteger();

	private ThreadOpenDiagnostics() {}

	public static void markModeState(boolean enabled, boolean active, int retainedPosts) {
		Log.d(TAG, "event=mode_state enabled=" + enabled + " active=" + active
				+ " retained_posts=" + retainedPosts);
		Trace.beginSection(TRACE_PREFIX + (active ? "mode_active" : enabled ? "mode_warm" : "mode_disabled"));
		Trace.endSection();
	}

	private static int nextCookie() {
		return NEXT_COOKIE.updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1);
	}

	public static int beginSession() {
		int sessionId = nextCookie();
		Log.d(TAG, "session=" + sessionId + " event=mode_enabled");
		Trace.beginAsyncSection(TRACE_PREFIX + "session", sessionId);
		return sessionId;
	}

	public static void endSession(int sessionId, String status) {
		if (sessionId <= 0) {
			return;
		}
		Log.d(TAG, "session=" + sessionId + " event=session_end status=" + status);
		Trace.endAsyncSection(TRACE_PREFIX + "session", sessionId);
	}

	public static Operation beginOperation(int sessionId, String name) {
		if (sessionId <= 0) {
			return null;
		}
		Operation operation = new Operation(sessionId, nextCookie(), name);
		Log.d(TAG, "session=" + sessionId + " event=" + name + "_start");
		Trace.beginAsyncSection(TRACE_PREFIX + name, operation.cookie);
		return operation;
	}

	public static void endOperation(Operation operation, String status, int posts, int totalPosts) {
		if (operation == null) {
			return;
		}
		synchronized (operation) {
			if (operation.finished) {
				return;
			}
			operation.finished = true;
		}
		long elapsed = SystemClock.elapsedRealtime() - operation.startRealtime;
		StringBuilder builder = new StringBuilder().append("session=").append(operation.sessionId)
				.append(" event=").append(operation.name).append("_end status=").append(status)
				.append(" elapsed_ms=").append(elapsed);
		appendCounts(builder, posts, totalPosts);
		Log.d(TAG, builder.toString());
		Trace.endAsyncSection(TRACE_PREFIX + operation.name, operation.cookie);
	}

	public static void mark(int sessionId, String event, int posts, int totalPosts) {
		if (sessionId <= 0) {
			return;
		}
		StringBuilder builder = new StringBuilder().append("session=").append(sessionId)
				.append(" event=").append(event);
		appendCounts(builder, posts, totalPosts);
		Log.d(TAG, builder.toString());
		Trace.beginSection(TRACE_PREFIX + event);
		Trace.endSection();
		if (posts >= 0) {
			Trace.setCounter(TRACE_PREFIX + "posts_ready", posts);
		}
		if (totalPosts >= 0) {
			Trace.setCounter(TRACE_PREFIX + "posts_total", totalPosts);
		}
	}

	private static void appendCounts(StringBuilder builder, int posts, int totalPosts) {
		if (posts >= 0) {
			builder.append(" posts=").append(posts);
		}
		if (totalPosts >= 0) {
			builder.append(" total_posts=").append(totalPosts);
		}
	}
}
