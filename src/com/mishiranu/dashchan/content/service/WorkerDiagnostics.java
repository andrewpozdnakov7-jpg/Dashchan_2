package com.mishiranu.dashchan.content.service;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import androidx.work.ListenableWorker;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.WorkQuery;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Debug-only aggregates. Never log input/output Data, work IDs, credentials or post identifiers. */
public final class WorkerDiagnostics {
	private static final String TAG = "WorkerDiagnostics";
	private static final HashMap<String, Long> LAST_QUEUE_SAMPLE = new HashMap<>();
	private static final ExecutorService EXECUTOR = ConcurrentUtils.newSingleThreadPool(3000, TAG, null);

	private WorkerDiagnostics() {}

	public static void event(ListenableWorker worker, String event, long durationMs) {
		if (!BuildConfig.DEBUG) return;
		Log.d(TAG, worker.getClass().getSimpleName() + " event=" + event + " durationMs=" + durationMs
				+ " attempt=" + worker.getRunAttemptCount() + " stopped=" + worker.isStopped()
				+ " stopReason=" + (Build.VERSION.SDK_INT >= 31 ? Integer.toString(worker.getStopReason()) : "unavailable"));
	}

	/** Pass only fixed internal unique names, never per-message delivery names. No UI-thread waits. */
	public static void sampleQueue(Context context, String uniqueName) {
		if (!BuildConfig.DEBUG) return;
		long now = SystemClock.elapsedRealtime();
		synchronized (LAST_QUEUE_SAMPLE) {
			Long previous = LAST_QUEUE_SAMPLE.get(uniqueName);
			if (previous != null && now - previous < 30_000L) return;
			LAST_QUEUE_SAMPLE.put(uniqueName, now);
		}
		Context applicationContext = context.getApplicationContext();
		EXECUTOR.execute(() -> {
			try {
				WorkQuery query = WorkQuery.Builder.fromUniqueWorkNames(Arrays.asList(uniqueName))
						.addStates(Arrays.asList(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING,
								WorkInfo.State.BLOCKED)).build();
				List<WorkInfo> work = WorkManager.getInstance(applicationContext).getWorkInfos(query)
						.get(5L, TimeUnit.SECONDS);
				int queued = 0, running = 0, blocked = 0, maxAttempt = 0;
				for (WorkInfo info : work) {
					switch (info.getState()) {
						case ENQUEUED: queued++; break;
						case RUNNING: running++; break;
						case BLOCKED: blocked++; break;
						default: break;
					}
					maxAttempt = Math.max(maxAttempt, info.getRunAttemptCount());
				}
				Log.d(TAG, "queue=" + uniqueName + " enqueued=" + queued + " running=" + running
						+ " blocked=" + blocked + " maxAttempt=" + maxAttempt);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				// Do not log exception messages: a database error can contain a private path or input data.
				Log.d(TAG, "queue=" + uniqueName + " sampleFailed=" + e.getClass().getSimpleName());
			}
		});
	}
}
