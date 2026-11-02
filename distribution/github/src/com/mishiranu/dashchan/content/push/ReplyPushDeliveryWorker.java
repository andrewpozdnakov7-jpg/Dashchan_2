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

/** Durable delivery and board-text read. Work input contains identifiers, never credentials or reply text. */
public final class ReplyPushDeliveryWorker extends Worker {
	private volatile ReplyPushTextReader textReader;
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
			// Return promptly from the messaging service; board I/O belongs to the durable worker.
			WorkManager.getInstance(context).enqueueUniqueWork("reply-delivery-" + message.eventId,
					ExistingWorkPolicy.KEEP, work).getResult().get(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		} catch (Exception e) {
			// A full/unavailable disk can prevent even the durable queue from accepting work.
			Logger.write(Logger.Type.ERROR, "ReplyPush", "delivery_enqueue_failed", e.getClass().getSimpleName());
			// If even the durable queue is unavailable, retain the old generic-notification fallback.
			ReplyPushManager.processData(context, values);
		}
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
		ReplyPushTextReader reader = textReader; if (reader != null) reader.close();
		WorkerDiagnostics.event(this, "stopped", 0L);
	}

	private Result deliver() {
		Map<String, String> values = new HashMap<>();
		for (Map.Entry<String, Object> entry : getInputData().getKeyValueMap().entrySet()) {
			if (!(entry.getValue() instanceof String)) return Result.failure();
			values.put(entry.getKey(), (String) entry.getValue());
		}
		ReplyPushMessage message = ReplyPushMessage.parse(values);
		if (message == null) return Result.failure();
		ReplyPushManager.ReplyTextPlan plan = ReplyPushManager.prepareReplyText(values, getRunAttemptCount() > 0);
		if (plan == ReplyPushManager.ReplyTextPlan.RETRY) return Result.retry();
		boolean fetch = plan == ReplyPushManager.ReplyTextPlan.READ;
		ReplyPushMessage resolved = null; boolean retryText = false;
		if (fetch && !isStopped()) {
			try (ReplyPushTextReader reader = new ReplyPushTextReader()) {
				textReader = reader; if (isStopped()) reader.close();
				resolved = reader.read(message);
				retryText = resolved == null && reader.isRetryable() && getRunAttemptCount() < 2;
			} catch (RuntimeException e) {
				Logger.write(Logger.Type.DEBUG, "ReplyPush", "reply_text_failed", e.getClass().getSimpleName());
				retryText = getRunAttemptCount() < 2;
			} finally { textReader = null; }
		}
		if (isStopped()) return Result.retry();
		boolean hadNotification = com.mishiranu.dashchan.content.WatcherNotifications.hasActiveReply(getApplicationContext(),
				message.chanName, message.boardName, message.threadNumber, message.replyPostNumber);
		ReplyPushManager.DeliveryResult result = ReplyPushManager.processData(getApplicationContext(), values, resolved);
		if (result != ReplyPushManager.DeliveryResult.RETRY && hadNotification
				&& (result == ReplyPushManager.DeliveryResult.HANDLED || resolved != null || getRunAttemptCount() > 0))
			com.mishiranu.dashchan.content.WatcherNotifications.refreshReplyContents(getApplicationContext());
		return result == ReplyPushManager.DeliveryResult.RETRY || retryText ? Result.retry() : Result.success();
	}
}
