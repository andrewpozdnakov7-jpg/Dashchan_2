package com.mishiranu.dashchan.content.push;

import android.content.Context;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.service.WorkerDiagnostics;
import com.mishiranu.dashchan.util.Logger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Durable, local-only retry. Work input contains validated post identifiers, never credentials or text. */
public final class ReplyPushDeliveryWorker extends Worker {
	public ReplyPushDeliveryWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
		super(context, parameters);
	}

	public static void enqueue(Context context, Map<String, String> values) {
		ReplyPushMessage message = ReplyPushMessage.parse(values);
		if (message == null || !Preferences.isTrackMyPostsEnabled() || !Preferences.isReplyPushEnabled()) return;
		Data.Builder input = new Data.Builder();
		for (Map.Entry<String, String> entry : values.entrySet()) input.putString(entry.getKey(), entry.getValue());
		OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ReplyPushDeliveryWorker.class)
				.setInputData(input.build())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build();
		try {
			// Confirm local persistence before relying on this retry, then attempt immediate delivery.
			WorkManager.getInstance(context).enqueueUniqueWork("reply-delivery-" + message.eventId,
					ExistingWorkPolicy.KEEP, work).getResult().get(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		} catch (Exception e) {
			// A full/unavailable disk can prevent even the durable queue from accepting work.
			Logger.write(Logger.Type.ERROR, "ReplyPush", "delivery_enqueue_failed", e.getClass().getSimpleName());
		}
		ReplyPushManager.processData(context, values);
	}

	@NonNull
	@Override
	public Result doWork() {
		long startedAt = SystemClock.elapsedRealtime();
		WorkerDiagnostics.event(this, "start", 0L);
		try {
			Result result = deliver();
			WorkerDiagnostics.event(this, result.getClass().getSimpleName(),
					SystemClock.elapsedRealtime() - startedAt);
			return result;
		} finally {
			WorkerDiagnostics.event(this, "finish", SystemClock.elapsedRealtime() - startedAt);
		}
	}

	@Override
	public void onStopped() {
		super.onStopped();
		WorkerDiagnostics.event(this, "stopped", 0L);
	}

	private Result deliver() {
		Map<String, String> values = new HashMap<>();
		for (Map.Entry<String, Object> entry : getInputData().getKeyValueMap().entrySet()) {
			if (!(entry.getValue() instanceof String)) return Result.failure();
			values.put(entry.getKey(), (String) entry.getValue());
		}
		if (ReplyPushMessage.parse(values) == null) return Result.failure();
		ReplyPushManager.DeliveryResult result = ReplyPushManager.processData(getApplicationContext(), values);
		return result == ReplyPushManager.DeliveryResult.RETRY ? Result.retry() : Result.success();
	}
}
