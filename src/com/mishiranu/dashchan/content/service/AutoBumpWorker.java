package com.mishiranu.dashchan.content.service;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import chan.content.ApiException;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import chan.content.ExtensionException;
import chan.content.InvalidResponseException;
import chan.content.RedirectException;
import chan.content.ThreadRedirectException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.AutoBumpNotifications;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.database.CommonDatabase;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.content.storage.AutoBumpStorage;
import com.mishiranu.dashchan.content.storage.StatisticsStorage;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AutoBumpWorker extends Worker {
	private static final String UNIQUE_WORK_NAME = "auto-bump";
	private static final String DVACH_CHAN_NAME = "dvach";
	private static final long TASK_SPACING_MILLIS = 60_000L;
	private static final long ACTIVITY_TIMESTAMP_TOLERANCE_MILLIS = 2_000L;

	public AutoBumpWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
		super(context, workerParams);
	}

	public static void restoreSchedule(Context context) {
		scheduleNext(context, ExistingWorkPolicy.KEEP, true, 0L);
	}

	public static void scheduleNext(Context context) {
		scheduleNext(context, ExistingWorkPolicy.REPLACE, true, 0L);
	}

	private static void scheduleNext(Context context, ExistingWorkPolicy policy, boolean cancelIfEmpty,
			long minimumDelay) {
		Context applicationContext = context.getApplicationContext();
		WorkManager workManager = WorkManager.getInstance(applicationContext);
		if (!Preferences.isAutoBumpEnabled()) {
			if (cancelIfEmpty) {
				workManager.cancelUniqueWork(UNIQUE_WORK_NAME);
			}
			return;
		}
		long nextRunAt = Long.MAX_VALUE;
		for (AutoBumpStorage.Task task : AutoBumpStorage.getInstance().getTasks()) {
			if (task.enabled) {
				nextRunAt = Math.min(nextRunAt, task.nextRunAt);
			}
		}
		if (nextRunAt == Long.MAX_VALUE) {
			if (cancelIfEmpty) {
				workManager.cancelUniqueWork(UNIQUE_WORK_NAME);
			}
			return;
		}
		long delay = Math.max(minimumDelay, Math.max(0L, nextRunAt - System.currentTimeMillis()));
		Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
		OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AutoBumpWorker.class)
				.setConstraints(constraints)
				.setInitialDelay(delay, TimeUnit.MILLISECONDS)
				.build();
		workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request);
	}

	@NonNull
	@Override
	public Result doWork() {
		if (!Preferences.isAutoBumpEnabled()) {
			return Result.success();
		}
		long now = System.currentTimeMillis();
		List<AutoBumpStorage.Task> tasks = AutoBumpStorage.getInstance().getTasks();
		tasks.sort(Comparator.comparingLong(task -> task.nextRunAt));
		boolean attemptedSend = false;
		for (AutoBumpStorage.Task task : tasks) {
			if (isStopped()) {
				return Result.retry();
			}
			if (task.enabled && task.nextRunAt <= now) {
				if (PostingService.isPostingInProgress()) {
					AutoBumpStorage.getInstance().postpone(task.id, now + 5 * 60_000L);
				} else {
					send(task, now);
					attemptedSend = true;
					break;
				}
			}
		}
		scheduleNext(getApplicationContext(), ExistingWorkPolicy.APPEND_OR_REPLACE, false,
				attemptedSend ? TASK_SPACING_MILLIS : 0L);
		return Result.success();
	}

	private void send(AutoBumpStorage.Task task, long now) {
		AutoBumpStorage storage = AutoBumpStorage.getInstance();
		if (!DVACH_CHAN_NAME.equals(task.chanName)) {
			pause(storage, task, getApplicationContext().getString(R.string.auto_bump_dvach_only), now);
			return;
		}
		Chan chan = Chan.get(task.chanName);
		List<String> pass = Preferences.getCaptchaPass(chan);
		if (!Preferences.checkHasMultipleValues(pass)) {
			pause(storage, task, getApplicationContext().getString(R.string.auto_bump_pass_required), now);
			return;
		}
		HttpHolder holder = new HttpHolder(chan);
		try (HttpHolder.Use ignored = holder.use()) {
			if (!checkThread(task, chan, holder, now)) {
				return;
			}
			if (PostingService.isPostingInProgress()) {
				storage.postpone(task.id, now + 5 * 60_000L);
				return;
			}
			String captchaType = Preferences.getCaptchaTypeForChan(chan);
			ChanPerformer.ReadCaptchaResult captcha;
			try {
				captcha = chan.performer.safe().onReadCaptcha(
						new ChanPerformer.ReadCaptchaData(captchaType, CommonUtils.toArray(pass, String.class),
								false, null, task.boardName, task.threadNumber, holder));
			} catch (InvalidResponseException e) {
				pause(storage, task, getApplicationContext().getString(R.string.auto_bump_pass_invalid), now);
				return;
			} catch (ExtensionException | HttpException e) {
				recordFailure(storage, task, e.getErrorItemAndHandle().toString(), false, now);
				return;
			}
			if (captcha == null || (captcha.captchaState != ChanPerformer.CaptchaState.PASS
					&& captcha.captchaState != ChanPerformer.CaptchaState.SKIP)) {
				pause(storage, task, getApplicationContext().getString(R.string.auto_bump_pass_invalid), now);
				return;
			}
			AutoBumpStorage.Task currentTask = storage.getTask(task.id);
			if (!Preferences.isAutoBumpEnabled() || currentTask == null || !currentTask.enabled
					|| currentTask.nextRunAt > now || !CommonUtils.equals(currentTask.boardName, task.boardName)
					|| !CommonUtils.equals(currentTask.threadNumber, task.threadNumber)
					|| !CommonUtils.equals(currentTask.message, task.message)
					|| currentTask.nextNumber != task.nextNumber) {
				return;
			}
			if (PostingService.isPostingInProgress()) {
				storage.postpone(task.id, now + 5 * 60_000L);
				return;
			}
			ChanPerformer.SendPostData data = new ChanPerformer.SendPostData(currentTask.boardName,
					currentTask.threadNumber, null, currentTask.formatMessage(), null, null,
					Preferences.getPassword(chan), null,
					false, false, false, null, captchaType, captcha.captchaData, false, 15000, 45000);
			data.holder = holder;
			ChanPerformer.SendPostResult result;
			try {
				result = chan.performer.safe().onSendPost(data);
			} catch (ApiException e) {
				ErrorItem error = e.getErrorItem();
				if (e.getErrorType() == ApiException.SEND_ERROR_TOO_FAST) {
					storage.postpone(currentTask.id, now + TASK_SPACING_MILLIS);
					return;
				}
				boolean permanent = e.getErrorType() == ApiException.SEND_ERROR_NO_BOARD
						|| e.getErrorType() == ApiException.SEND_ERROR_NO_THREAD
						|| e.getErrorType() == ApiException.SEND_ERROR_CLOSED
						|| e.getErrorType() == ApiException.SEND_ERROR_CAPTCHA
						|| e.getErrorType() == ApiException.SEND_ERROR_NO_ACCESS
						|| e.getErrorType() == ApiException.SEND_ERROR_BANNED
						|| e.getErrorType() == ApiException.SEND_ERROR_FIELD_TOO_LONG
						|| e.getErrorType() == ApiException.SEND_ERROR_SPAM_LIST
						|| e.getErrorType() == ApiException.SEND_ERROR_EMPTY_COMMENT;
				recordFailure(storage, currentTask, error.toString(), permanent, now);
				return;
			} catch (ExtensionException | HttpException | InvalidResponseException e) {
				String reason = getApplicationContext().getString(R.string.auto_bump_delivery_unknown__format,
						e.getErrorItemAndHandle().toString());
				pause(storage, currentTask, reason, now);
				return;
			}
			if (result == null) {
				String reason = getApplicationContext().getString(R.string.auto_bump_delivery_unknown__format,
						getApplicationContext().getString(R.string.invalid_server_response));
				pause(storage, currentTask, reason, now);
				return;
			}
			String targetThreadNumber = result.threadNumber != null
					? result.threadNumber : currentTask.threadNumber;
			String comment = data.comment;
			ConcurrentUtils.mainGet(() -> {
				if (result.postNumber != null) {
					CommonDatabase.getInstance().getPosts().setFlags(true, currentTask.chanName,
							currentTask.boardName,
							targetThreadNumber, result.postNumber, PostItem.HideState.UNDEFINED, true);
				} else {
					PostingService.registerPendingUserPost(currentTask.chanName, currentTask.boardName,
							targetThreadNumber, comment);
				}
				StatisticsStorage.getInstance().incrementPostsSent(currentTask.chanName, false);
				return null;
			});
			storage.updateAfterSuccess(currentTask.id, now,
					result.postNumber != null ? result.postNumber.toString() : null);
		} finally {
			chan.configuration.commit();
		}
	}

	private boolean checkThread(AutoBumpStorage.Task task, Chan chan, HttpHolder holder, long now) {
		AutoBumpStorage storage = AutoBumpStorage.getInstance();
		ChanPerformer.ReadPostsResult result;
		try {
			result = chan.performer.safe().onReadPosts(new ChanPerformer.ReadPostsData(chan.name,
					task.boardName, task.threadNumber, null, false, false, holder, null));
		} catch (RedirectException | ThreadRedirectException e) {
			pause(storage, task, getApplicationContext().getString(R.string.auto_bump_thread_unavailable), now);
			return false;
		} catch (HttpException e) {
			if (e.getResponseCode() == 404) {
				pause(storage, task, getApplicationContext().getString(R.string.auto_bump_thread_unavailable), now);
			} else {
				recordFailure(storage, task, e.getErrorItemAndHandle().toString(), false, now);
			}
			return false;
		} catch (ExtensionException | InvalidResponseException e) {
			recordFailure(storage, task, e.getErrorItemAndHandle().toString(), false, now);
			return false;
		}
		if (result == null || result.posts.isEmpty()) {
			recordFailure(storage, task, getApplicationContext().getString(R.string.invalid_server_response),
					false, now);
			return false;
		}
		Post originalPost = null;
		Post latestBumpPost = null;
		for (Post post : result.posts) {
			if (originalPost == null || post.number.compareTo(originalPost.number) < 0) {
				originalPost = post;
			}
			if (!post.deleted && !post.isSage()
					&& (latestBumpPost == null || post.number.compareTo(latestBumpPost.number) > 0)) {
				latestBumpPost = post;
			}
		}
		if (originalPost == null) {
			recordFailure(storage, task, getApplicationContext().getString(R.string.invalid_server_response),
					false, now);
			return false;
		}
		if (originalPost.isClosed() || originalPost.isArchived()) {
			pause(storage, task, getApplicationContext().getString(R.string.auto_bump_thread_unavailable), now);
			return false;
		}
		int bumpLimit = chan.configuration.getBumpLimitWithMode(task.boardName);
		if (!originalPost.isCyclical() && (originalPost.isBumpLimitReached()
				|| bumpLimit != ChanConfiguration.BUMP_LIMIT_INVALID && result.posts.size() >= bumpLimit)) {
			pause(storage, task, getApplicationContext().getString(R.string.auto_bump_bump_limit_reached), now);
			return false;
		}
		if (latestBumpPost != null && isNewBumpActivity(task, latestBumpPost)) {
			long activityAt = latestBumpPost.timestamp;
			if (activityAt <= task.lastActivityAt || activityAt > now) {
				activityAt = now;
			}
			storage.updateAfterThreadActivity(task.id, latestBumpPost.number.toString(), activityAt);
			return AutoBumpStorage.calculateNextRunAt(activityAt, task.intervalMinutes) <= now;
		}
		return true;
	}

	private boolean isNewBumpActivity(AutoBumpStorage.Task task, Post latestBumpPost) {
		if (!StringUtils.isEmpty(task.lastBumpPostNumber)) {
			try {
				return latestBumpPost.number.compareTo(PostNumber.parseOrThrow(task.lastBumpPostNumber)) > 0;
			} catch (RuntimeException e) {
				// Fall through to timestamp comparison for data saved by an older version.
			}
		}
		return latestBumpPost.timestamp > 0L && latestBumpPost.timestamp >=
				Math.max(0L, task.lastActivityAt - ACTIVITY_TIMESTAMP_TOLERANCE_MILLIS);
	}

	private void recordFailure(AutoBumpStorage storage, AutoBumpStorage.Task task, String reason,
			boolean permanent, long now) {
		AutoBumpStorage.Task updated = storage.updateAfterFailure(task.id, reason, permanent, now);
		if (updated != null && !updated.enabled) {
			AutoBumpNotifications.notifyPaused(getApplicationContext(), updated, reason);
		}
	}

	private void pause(AutoBumpStorage storage, AutoBumpStorage.Task task, String reason, long now) {
		AutoBumpStorage.Task updated = storage.updateAfterFailure(task.id, reason, true, now);
		if (updated != null) {
			AutoBumpNotifications.notifyPaused(getApplicationContext(), updated, reason);
		}
	}
}
