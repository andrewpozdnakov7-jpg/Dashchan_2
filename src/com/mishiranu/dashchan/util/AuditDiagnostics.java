package com.mishiranu.dashchan.util;

import android.os.Trace;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;

/** Bounded, process-local audit metrics. Labels/reasons/results MUST be fixed code literals, never user data. */
public final class AuditDiagnostics {
	private AuditDiagnostics() {}
	private static final Map<String, Stats> STATS = new HashMap<>();
	private static long lastSlow;
	private static final java.util.concurrent.atomic.AtomicInteger NEXT_SPAN = new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicBoolean SUMMARY_PENDING = new java.util.concurrent.atomic.AtomicBoolean();
	private static final class Reporter {
		static final java.util.concurrent.Executor WORKER = ConcurrentUtils.newSingleThreadPool(1000, "AuditSummary", null);
	}
	private static final class Stats {
		long count, totalNs, maxNs, failures, lastReport;
		final long[] bins = new long[6];
	}


	/** Flush cumulative tails when leaving the app, off the UI thread and with at most one queued report. */
	public static void requestSummary() {
		if (!SUMMARY_PENDING.compareAndSet(false, true)) return;
		Reporter.WORKER.execute(() -> {
			try {
				String[] keys;
				synchronized (STATS) { keys = STATS.keySet().toArray(new String[0]); }
				for (String key : keys) {
					long count, total, max, failures;
					long[] bins;
					synchronized (STATS) {
						Stats stats = STATS.get(key);
						count = stats.count; total = stats.totalNs; max = stats.maxNs; failures = stats.failures;
						bins = stats.bins.clone();
					}
					Log.d("SlooopAuditPerf", "event=checkpoint operation=" + key + " calls=" + count
							+ " total_us=" + total / 1000 + " max_us=" + max / 1000 + " failures=" + failures
							+ " bins_lt1_lt4_lt8_lt16_lt32_ge32=" + java.util.Arrays.toString(bins));
				}
			} finally {
				SUMMARY_PENDING.set(false);
			}
		});
	}

	public static Scope begin(String operation) { return new Scope(operation, false); }
	public static Scope beginAsync(String operation) { return new Scope(operation, true); }
	public static <T> T get(String operation, java.util.function.Supplier<T> action) {
		try (Scope scope = begin(operation)) {
			T value = action.get();
			scope.result("ok");
			return value;
		}
	}
	public static void run(String operation, Runnable action) {
		try (Scope scope = begin(operation)) {
			action.run();
			scope.result("ok");
		}
	}

	public static final class Scope implements AutoCloseable {
		private final String operation;
		private final boolean async;
		private final int cookie;
		private final boolean main = android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
		private final long started;
		private String reason = "default", result = "exception";
		private long oldSize = -1, newSize = -1, count = -1;
		private boolean closed;
		private Scope(String operation, boolean async) {
			this.operation = operation;
			this.async = async;
			cookie = async ? NEXT_SPAN.incrementAndGet() : 0;
			if (async) Trace.beginAsyncSection(operation, cookie);
			else Trace.beginSection(operation);
			started = PerformanceDiagnostics.now();
		}
		public Scope reason(String reason) {
			this.reason = reason;
			if (Trace.isEnabled()) { Trace.beginSection("AuditReason/" + reason); Trace.endSection(); }
			return this;
		}
		public Scope sizes(long oldSize, long newSize) {
			this.oldSize = oldSize; this.newSize = newSize;
			if (Trace.isEnabled()) {
				Trace.setCounter(operation + "/oldSize", oldSize);
				Trace.setCounter(operation + "/newSize", newSize);
			}
			return this;
		}
		public Scope count(long count) {
			this.count = count;
			if (Trace.isEnabled()) Trace.setCounter(operation + "/count", count);
			return this;
		}
		public void result(String result) {
			this.result = result;
			if (Trace.isEnabled()) { Trace.beginSection("AuditResult/" + result); Trace.endSection(); }
		}
		@Override public void close() {
			if (closed) return;
			closed = true;
			long now = PerformanceDiagnostics.now(), elapsed = now - started;
			if (async) Trace.endAsyncSection(operation, cookie);
			else Trace.endSection();
			Trace.beginSection("AuditDiagnostics/report");
			try {
				String key = operation + "/" + reason + (main ? "/main" : "/worker");
				String summary = null;
				boolean slow;
				synchronized (STATS) {
					Stats stats = STATS.get(key);
					if (stats == null && STATS.size() < 256) { stats = new Stats(); STATS.put(key, stats); }
					if (stats != null) {
						stats.count++; stats.totalNs += elapsed; stats.maxNs = Math.max(stats.maxNs, elapsed);
						if ("exception".equals(result) || "failed".equals(result)) stats.failures++;
						stats.bins[elapsed < 1000000 ? 0 : elapsed < 4000000 ? 1 : elapsed < 8000000 ? 2
								: elapsed < 16000000 ? 3 : elapsed < 32000000 ? 4 : 5]++;
						// First sample and cumulative summaries; no timer/work is kept alive in background.
						if (stats.count == 1 || now - stats.lastReport >= 10000000000L) {
							stats.lastReport = now;
							summary = "event=summary operation=" + key + " calls=" + stats.count
									+ " total_us=" + stats.totalNs / 1000 + " max_us=" + stats.maxNs / 1000
									+ " failures=" + stats.failures + " bins_lt1_lt4_lt8_lt16_lt32_ge32="
									+ java.util.Arrays.toString(stats.bins);
						}
					}
					slow = (elapsed >= 8000000 || "exception".equals(result) || "failed".equals(result))
							&& now - lastSlow >= 1000000000L;
					if (slow) lastSlow = now;
				}
				if (summary != null) Log.d("SlooopAuditPerf", summary + details(elapsed));
				if (slow) Log.w("SlooopAuditPerf", "event=slow operation=" + key + details(elapsed) + slowSource());
			} finally {
				Trace.endSection();
			}
		}

		// Only on the globally rate-limited slow sample; method names only, no file paths/arguments.
		private String slowSource() {
			StringBuilder source = new StringBuilder(" source=");
			int count = 0;
			for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
				String name = frame.getClassName();
				if ((name.startsWith("com.mishiranu.dashchan.") || name.startsWith("chan.content."))
						&& !name.startsWith(AuditDiagnostics.class.getName())
						&& !name.startsWith("com.mishiranu.dashchan.util.SharedPreferences")
						&& !name.equals(PerformanceDiagnostics.class.getName())) {
					if (count++ > 0) source.append('>');
					source.append(name).append('#').append(frame.getMethodName());
					if (count == 3) break;
				}
			}
			return source.toString();
		}

		private String details(long elapsed) {
			return " duration_us=" + elapsed / 1000 + " old=" + oldSize + " new=" + newSize
					+ " count=" + count + " result=" + result;
		}
	}

}
