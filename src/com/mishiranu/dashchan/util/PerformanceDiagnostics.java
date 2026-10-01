package com.mishiranu.dashchan.util;

import android.os.Looper;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;

/** Local lifecycle timings, including production builds. Labels must be fixed, non-user strings. */
public final class PerformanceDiagnostics {
	private PerformanceDiagnostics() {}

	public static long now() {
		return SystemClock.elapsedRealtimeNanos();
	}

	public static void run(String operation, Runnable action) {
		long started = now();
		boolean success = false;
		Trace.beginSection(operation);
		try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin("Audit/" + operation)) {
			action.run();
			success = true;
			scope.result("ok");
		} finally {
			Trace.endSection();
			finish(operation, started, success, false);
		}
	}

	public static void finish(String operation, long started, boolean success, boolean slowOnly) {
		long millis = (now() - started) / 1000000L;
		if (slowOnly && success && millis < 8) return;
		boolean main = Looper.myLooper() == Looper.getMainLooper();
		Log.println(main && millis >= 16 || !success ? Log.WARN : Log.DEBUG, "SlooopPerf",
				"operation=" + operation + " duration_ms=" + millis + " main=" + main + " success=" + success);
	}
}
