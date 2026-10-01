package com.mishiranu.dashchan.util;

import android.os.Looper;
import android.os.Trace;
import android.util.Log;
import java.util.LinkedHashMap;
import java.util.Map;

/** Navigation timings only: fixed call-site labels, no URLs, titles, arguments or stack traces. */
public final class FragmentAccessDiagnostics {
	private FragmentAccessDiagnostics() {}
	private static final String TAG = "SlooopFragmentPerf";
	private static final long SLOW_NS = 8_000_000L, LOG_INTERVAL_NS = 1_000_000_000L;
	private static final Map<String, Stats> STATS = new LinkedHashMap<>();
	private static int depth;
	private static long lastDetail;
	private static boolean reportPending;

	private static final class Stats {
		long calls, executed, rejected, failures, nested, nullResults;
		long totalNs, executeNs, findNs, maxNs, maxExecuteNs, maxFindNs;
		long under1, under4, under8, under16, under32, over32;
	}

	public static long begin(String callSite) {
		Trace.beginSection(callSite);
		if (Looper.myLooper() == Looper.getMainLooper()) depth++;
		return PerformanceDiagnostics.now();
	}

	public static void end(String callSite, long started, long executeNs, long findNs,
			boolean executed, boolean rejected, boolean success, boolean nullResult) {
		long ended = PerformanceDiagnostics.now();
		Trace.endSection();
		// FragmentManager requires the main thread. Do not change failure behavior for other callers.
		if (Looper.myLooper() != Looper.getMainLooper()) return;
		boolean nested = depth > 1;
		depth--;
		long totalNs = ended - started;
		Stats stats = STATS.get(callSite);
		if (stats == null) {
			stats = new Stats();
			STATS.put(callSite, stats);
		}
		stats.calls++;
		if (executed) stats.executed++;
		if (rejected) stats.rejected++;
		if (!success) stats.failures++;
		if (nested) stats.nested++;
		if (nullResult) stats.nullResults++;
		stats.totalNs += totalNs;
		stats.executeNs += executeNs;
		stats.findNs += findNs;
		stats.maxNs = Math.max(stats.maxNs, totalNs);
		stats.maxExecuteNs = Math.max(stats.maxExecuteNs, executeNs);
		stats.maxFindNs = Math.max(stats.maxFindNs, findNs);
		if (totalNs < 1_000_000L) stats.under1++;
		else if (totalNs < 4_000_000L) stats.under4++;
		else if (totalNs < SLOW_NS) stats.under8++;
		else if (totalNs < 16_000_000L) stats.under16++;
		else if (totalNs < 32_000_000L) stats.under32++;
		else stats.over32++;
		// Bound logcat overhead. Every call still has trace slices and is counted in the summary.
		if ((totalNs >= SLOW_NS || rejected || !success) && ended - lastDetail >= LOG_INTERVAL_NS) {
			lastDetail = ended;
			Log.d(TAG, "sample site=" + callSite + " total_us=" + totalNs / 1000
					+ " execute_us=" + executeNs / 1000 + " find_us=" + findNs / 1000
					+ " executed=" + executed + " rejected=" + rejected + " success=" + success
					+ " nested=" + nested + " null=" + nullResult);
		}
		if (!reportPending) {
			reportPending = true;
			ConcurrentUtils.HANDLER.postDelayed(FragmentAccessDiagnostics::report, 10_000L);
		}
	}

	private static void report() {
		reportPending = false;
		Trace.beginSection("FragmentAccessDiagnostics/report");
		try {
			reportStats();
		} finally {
			Trace.endSection();
		}
	}

	private static void reportStats() {
		// Windowed, inclusive wall times: nested calls must not be summed as CPU time.
		for (Map.Entry<String, Stats> entry : STATS.entrySet()) {
			Stats s = entry.getValue();
			Log.d(TAG, "summary site=" + entry.getKey() + " calls=" + s.calls
					+ " executed=" + s.executed + " rejected=" + s.rejected + " failures=" + s.failures
					+ " nested=" + s.nested + " nulls=" + s.nullResults
					+ " total_us=" + s.totalNs / 1000 + " execute_us=" + s.executeNs / 1000
					+ " find_us=" + s.findNs / 1000 + " max_us=" + s.maxNs / 1000
					+ " max_execute_us=" + s.maxExecuteNs / 1000 + " max_find_us=" + s.maxFindNs / 1000
					+ " bins_ms_lt1_4_8_16_32_ge32=" + s.under1 + "," + s.under4 + "," + s.under8
					+ "," + s.under16 + "," + s.under32 + "," + s.over32);
		}
		STATS.clear();
	}
}
