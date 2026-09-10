package com.mishiranu.dashchan.content.service;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.RedirectException;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.WatcherNotifications;
import com.mishiranu.dashchan.content.async.ReadPostsTask;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.PendingUserPost;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.push.ReplyPushManager;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.content.storage.MyPostsStorage;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.Logger;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class BackgroundWatcherWorker extends Worker {
	private static final String LOG_TAG = "BackgroundWatcher";
	private static final String UNIQUE_WORK_NAME = "background-reply-check";
	private static final long INTERVAL_MINUTES = 15;
	private static final long MAX_RUN_MINUTES = 8;
	private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);
	private static final long WEEK_MILLIS = TimeUnit.DAYS.toMillis(7);
	private static final long MONTH_MILLIS = TimeUnit.DAYS.toMillis(30);
	private static final Object RUN_LOCK = new Object();
	private static BackgroundWatcherWorker currentRun;

	private final List<ReadPostsTask> tasks = Collections.synchronizedList(new ArrayList<>());
	private final AtomicBoolean acceptResults = new AtomicBoolean();
	private volatile ExecutorService executor;
	private volatile CountDownLatch completionLatch;
	private volatile boolean stoppedForForeground;

	private static void log(Object... data) {
		Logger.write(Logger.Type.DEBUG, LOG_TAG, data);
	}

	private static void logError(Object... data) {
		Logger.write(Logger.Type.ERROR, LOG_TAG, data);
	}

	private static class RunStats {
		public final AtomicInteger started = new AtomicInteger();
		public final AtomicInteger succeeded = new AtomicInteger();
		public final AtomicInteger redirected = new AtomicInteger();
		public final AtomicInteger failed = new AtomicInteger();
		public final AtomicInteger rawReplies = new AtomicInteger();
		public final AtomicInteger filteredReplies = new AtomicInteger();
		public final AtomicInteger queuedNotifications = new AtomicInteger();
		public final AtomicInteger suppressedNotifications = new AtomicInteger();
	}

	private static class CheckTarget {
		public final String chanName;
		public final String boardName;
		public final String threadNumber;
		public final FavoritesStorage.FavoriteItem favoriteItem;
		public final boolean tracked;

		public CheckTarget(String chanName, String boardName, String threadNumber,
				FavoritesStorage.FavoriteItem favoriteItem, boolean tracked) {
			this.chanName = chanName;
			this.boardName = boardName;
			this.threadNumber = threadNumber;
			this.favoriteItem = favoriteItem;
			this.tracked = tracked;
		}
	}

	private static String makeTargetKey(String chanName, String boardName, String threadNumber) {
		return chanName + "\n" + StringUtils.emptyIfNull(boardName) + "\n" + threadNumber;
	}

	private static long getCheckIntervalMillis(long now, Post lastPost) {
		if (lastPost == null || lastPost.timestamp <= 0L || lastPost.timestamp > now) {
			return TimeUnit.MINUTES.toMillis(INTERVAL_MINUTES);
		}
		long age = now - lastPost.timestamp;
		if (age < DAY_MILLIS) {
			return TimeUnit.MINUTES.toMillis(INTERVAL_MINUTES);
		} else if (age < WEEK_MILLIS) {
			return TimeUnit.MINUTES.toMillis(30);
		} else if (age < MONTH_MILLIS) {
			return TimeUnit.HOURS.toMillis(1);
		} else {
			return TimeUnit.HOURS.toMillis(2);
		}
	}

	private static boolean isAutomaticCheckEnabled() {
		return Preferences.isBackgroundReplyCheckEnabled() || Preferences.isTrackMyPostsEnabled()
				&& Preferences.isTrackedRepliesLocalCheckEnabled();
	}

	public BackgroundWatcherWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
		super(context, workerParams);
	}

	public static void updateSchedule(Context context) {
		updateSchedule(context, ExistingPeriodicWorkPolicy.UPDATE);
	}

	public static void restoreSchedule(Context context) {
		updateSchedule(context, ExistingPeriodicWorkPolicy.KEEP);
	}

	private static void updateSchedule(Context context, ExistingPeriodicWorkPolicy policy) {
		Context applicationContext = context.getApplicationContext();
		WorkManager workManager = WorkManager.getInstance(applicationContext);
		boolean enabled = isAutomaticCheckEnabled();
		boolean wifiOnly = Preferences.isWatcherWifiOnly();
		log("schedule", "policy", policy, "enabled", enabled,
				"backgroundReplies", Preferences.isBackgroundReplyCheckEnabled(),
				"trackedPosts", Preferences.isTrackMyPostsEnabled(),
				"trackedLocalCheck", Preferences.isTrackedRepliesLocalCheckEnabled(),
				"wifiOnly", wifiOnly);
		if (enabled) {
			NetworkType networkType = wifiOnly
					? NetworkType.UNMETERED : NetworkType.CONNECTED;
			Constraints constraints = new Constraints.Builder()
					.setRequiredNetworkType(networkType)
					.build();
			PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(BackgroundWatcherWorker.class,
					INTERVAL_MINUTES, TimeUnit.MINUTES)
					.setConstraints(constraints)
					.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INTERVAL_MINUTES, TimeUnit.MINUTES)
					.build();
			workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, policy, request);
		} else {
			workManager.cancelUniqueWork(UNIQUE_WORK_NAME);
		}
	}

	private static boolean isApplicationVisible() {
		// The bound watcher already refreshes threads while the application is visible.
		ActivityManager.RunningAppProcessInfo processInfo = new ActivityManager.RunningAppProcessInfo();
		ActivityManager.getMyMemoryState(processInfo);
		return processInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
	}

	private static int getApplicationImportance() {
		ActivityManager.RunningAppProcessInfo processInfo = new ActivityManager.RunningAppProcessInfo();
		ActivityManager.getMyMemoryState(processInfo);
		return processInfo.importance;
	}

	private void logRuntimeState() {
		Context context = getApplicationContext();
		ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
		ConnectivityManager connectivityManager = (ConnectivityManager)
				context.getSystemService(Context.CONNECTIVITY_SERVICE);
		Network network = connectivityManager.getActiveNetwork();
		NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
		NotificationManager notificationManager = (NotificationManager)
				context.getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationChannel channel = notificationManager.getNotificationChannel(C.NOTIFICATION_CHANNEL_REPLIES);
		log("runtime", "importance", getApplicationImportance(),
				"backgroundRestricted", activityManager.isBackgroundRestricted(),
				"deviceIdle", powerManager.isDeviceIdleMode(), "powerSave", powerManager.isPowerSaveMode(),
				"interactive", powerManager.isInteractive(),
				"batteryOptimizationExempt", powerManager.isIgnoringBatteryOptimizations(context.getPackageName()),
				"networkMetered", connectivityManager.isActiveNetworkMetered(),
				"networkAvailable", capabilities != null,
				"networkValidated", capabilities != null
						&& capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
				"networkUnmetered", capabilities != null
						&& capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
				"networkWifi", capabilities != null
						&& capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
				"networkCellular", capabilities != null
						&& capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
				"notificationsEnabled", notificationManager.areNotificationsEnabled(),
				"channelImportance", channel != null ? channel.getImportance() : "missing",
				"channelSound", channel != null && channel.getSound() != null,
				"channelVibration", channel != null && channel.shouldVibrate(),
				"interruptionFilter", notificationManager.getCurrentInterruptionFilter());
	}

	static void cancelForForeground() {
		BackgroundWatcherWorker worker;
		synchronized (RUN_LOCK) {
			worker = currentRun;
		}
		if (worker != null) {
			log("cancel", "reason", "foreground_watcher");
			worker.stopRun(true);
		}
	}

	private boolean beginRun() {
		if (!isAutomaticCheckEnabled()) {
			log("skip", "reason", "automatic_check_disabled");
			return false;
		}
		synchronized (RUN_LOCK) {
			if (currentRun != null && currentRun != this) {
				log("skip", "reason", "another_run_active");
				return false;
			}
			currentRun = this;
			acceptResults.set(true);
		}
		if (isApplicationVisible()) {
			log("skip", "reason", "application_visible", "importance", getApplicationImportance());
			stopRun(true);
			finishRun();
			return false;
		}
		return true;
	}

	private void finishRun() {
		acceptResults.set(false);
		completionLatch = null;
		synchronized (RUN_LOCK) {
			if (currentRun == this) {
				currentRun = null;
			}
		}
	}

	@NonNull
	@Override
	public Result doWork() {
		long startedAt = SystemClock.elapsedRealtime();
		log("run_start", "attempt", getRunAttemptCount(), "stopped", isStopped());
		logRuntimeState();
		if (!beginRun()) {
			log("run_finish", "result", "success_skipped", "durationMs",
					SystemClock.elapsedRealtime() - startedAt);
			return Result.success();
		}
		try {
			Result result = runCheck();
			log("run_finish", "result", result, "durationMs", SystemClock.elapsedRealtime() - startedAt);
			return result;
		} finally {
			finishRun();
		}
	}

	private Result runCheck() {
		List<CheckTarget> targets = ConcurrentUtils.mainGet(() -> {
			LinkedHashMap<String, CheckTarget> result = new LinkedHashMap<>();
			for (FavoritesStorage.FavoriteItem favoriteItem : FavoritesStorage.getInstance().getThreads(null)) {
				if (favoriteItem.watcherEnabled) {
					FavoritesStorage.FavoriteItem copy = new FavoritesStorage.FavoriteItem(favoriteItem);
					result.put(makeTargetKey(copy.chanName, copy.boardName, copy.threadNumber),
							new CheckTarget(copy.chanName, copy.boardName, copy.threadNumber, copy, false));
				}
			}
			if (Preferences.isTrackMyPostsEnabled() && Preferences.isTrackedRepliesLocalCheckEnabled()) {
				for (MyPostsStorage.ThreadKey key : MyPostsStorage.getInstance().getActiveThreadKeys()) {
					String targetKey = makeTargetKey(key.chanName, key.boardName, key.threadNumber);
					CheckTarget target = result.get(targetKey);
					result.put(targetKey, new CheckTarget(key.chanName, key.boardName, key.threadNumber,
							target != null ? target.favoriteItem : null, true));
				}
			}
			return new ArrayList<>(result.values());
		});
		int collectedTargets = targets.size();
		targets.removeIf(target -> {
			Chan chan = Chan.get(target.chanName);
			return chan.name == null || chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE);
		});
		int eligibleTargets = targets.size();
		int favoriteTargets = 0;
		int trackedTargets = 0;
		for (CheckTarget target : targets) {
			if (target.favoriteItem != null) {
				favoriteTargets++;
			}
			if (target.tracked) {
				trackedTargets++;
			}
		}
		long now = System.currentTimeMillis();
		PagesDatabase database = PagesDatabase.getInstance();
		ArrayList<CheckTarget> dueTargets = new ArrayList<>(targets.size());
		int deferredTargets = 0;
		int recentTargets = 0;
		int dailyTargets = 0;
		int weeklyTargets = 0;
		int oldTargets = 0;
		for (CheckTarget target : targets) {
			PagesDatabase.ThreadKey threadKey = new PagesDatabase.ThreadKey(target.chanName,
					target.boardName, target.threadNumber);
			Post lastPost = database.getLastExistingPost(threadKey);
			long interval = getCheckIntervalMillis(now, lastPost);
			if (interval <= TimeUnit.MINUTES.toMillis(INTERVAL_MINUTES)) {
				recentTargets++;
			} else if (interval <= TimeUnit.MINUTES.toMillis(30)) {
				dailyTargets++;
			} else if (interval <= TimeUnit.HOURS.toMillis(1)) {
				weeklyTargets++;
			} else {
				oldTargets++;
			}
			PagesDatabase.WatcherState watcherState = database.getWatcherState(threadKey);
			if (watcherState.time <= 0L || now - watcherState.time >= interval) {
				dueTargets.add(target);
			} else {
				deferredTargets++;
			}
		}
		targets = dueTargets;
		log("targets", "collected", collectedTargets, "eligible", eligibleTargets,
				"due", targets.size(), "filtered", collectedTargets - eligibleTargets,
				"deferred", deferredTargets, "favorites", favoriteTargets,
				"tracked", trackedTargets, "recent", recentTargets, "oneToSevenDays", dailyTargets,
				"sevenToThirtyDays", weeklyTargets, "olderThanThirtyDays", oldTargets);
		if (targets.isEmpty()) {
			log("skip", "reason", eligibleTargets > 0 ? "no_due_targets" : "no_eligible_targets");
			return Result.success();
		}

		Context context = getApplicationContext();
		WatcherNotifications.configure(context);
		int notificationColor = ConcurrentUtils.mainGet(() -> ThemeEngine.attachAndApply(context).accent);
		Set<Preferences.NotificationFeature> notificationFeatures = Preferences.getWatcherNotifications();
		CountDownLatch latch = new CountDownLatch(targets.size());
		RunStats stats = new RunStats();
		completionLatch = latch;
		executor = ConcurrentUtils.newThreadPool(3, 3, 0, "BackgroundWatcher", null);

		for (CheckTarget target : targets) {
			if (!acceptResults.get() || isApplicationVisible()) {
				log("stop", "reason", "application_became_visible", "importance",
						getApplicationImportance(), "startedTargets", stats.started.get());
				stopRun(true);
				break;
			}
			Set<PendingUserPost> pendingUserPosts = ConcurrentUtils.mainGet(() -> {
				Set<PendingUserPost> pending = PostingService.getPendingUserPosts(target.chanName,
						target.boardName, target.threadNumber);
				return pending != null ? new HashSet<>(pending) : null;
			});
			ReadPostsTask.Callback callback = new Callback(context, target, notificationColor,
					notificationFeatures, latch, acceptResults, stats);
			ReadPostsTask task = new ReadPostsTask(callback, Chan.get(target.chanName),
					target.boardName, target.threadNumber, false, pendingUserPosts);
			tasks.add(task);
			try {
				task.execute(executor);
				stats.started.incrementAndGet();
			} catch (RuntimeException e) {
				stats.failed.incrementAndGet();
				logError("target_rejected", "error", e.getClass().getName());
				tasks.remove(task);
				latch.countDown();
			}
		}

		boolean complete;
		try {
			complete = latch.await(MAX_RUN_MINUTES, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			cancelTasks();
			log("check_finish", "result", "retry_interrupted", "started", stats.started.get(),
					"succeeded", stats.succeeded.get(), "failed", stats.failed.get());
			return Result.retry();
		} finally {
			completionLatch = null;
			ExecutorService executor = this.executor;
			if (executor != null) {
				executor.shutdownNow();
			}
			this.executor = null;
		}
		if (stoppedForForeground) {
			logRunStats("success_foreground", stats);
			return Result.success();
		} else if (!complete) {
			cancelTasks();
			logRunStats("retry_timeout", stats);
			return Result.retry();
		}
		logRunStats("success", stats);
		return Result.success();
	}

	private static void logRunStats(String result, RunStats stats) {
		log("check_finish", "result", result, "started", stats.started.get(),
				"succeeded", stats.succeeded.get(), "redirected", stats.redirected.get(),
				"failed", stats.failed.get(), "rawReplies", stats.rawReplies.get(),
				"afterPushFilter", stats.filteredReplies.get(),
				"notificationsQueued", stats.queuedNotifications.get(),
				"notificationsSuppressed", stats.suppressedNotifications.get());
	}

	@Override
	public void onStopped() {
		super.onStopped();
		log("stop", "reason", "work_manager", "tasks", tasks.size());
		stopRun(false);
	}

	private void stopRun(boolean forForeground) {
		if (forForeground) {
			stoppedForForeground = true;
		}
		acceptResults.set(false);
		cancelTasks();
		ExecutorService executor = this.executor;
		if (executor != null) {
			executor.shutdownNow();
		}
		CountDownLatch latch = completionLatch;
		if (latch != null) {
			while (latch.getCount() > 0) {
				latch.countDown();
			}
		}
	}

	private void cancelTasks() {
		synchronized (tasks) {
			for (ReadPostsTask task : tasks) {
				task.cancel();
			}
			tasks.clear();
		}
	}

	private static class Callback implements ReadPostsTask.Callback {
		private final Context context;
		private final CheckTarget target;
		private final int notificationColor;
		private final Set<Preferences.NotificationFeature> notificationFeatures;
		private final CountDownLatch latch;
		private final AtomicBoolean acceptResults;
		private final RunStats stats;
		private final AtomicBoolean finished = new AtomicBoolean();

		public Callback(Context context, CheckTarget target, int notificationColor,
				Set<Preferences.NotificationFeature> notificationFeatures, CountDownLatch latch,
				AtomicBoolean acceptResults, RunStats stats) {
			this.context = context;
			this.target = target;
			this.notificationColor = notificationColor;
			this.notificationFeatures = notificationFeatures;
			this.latch = latch;
			this.acceptResults = acceptResults;
			this.stats = stats;
		}

		@Override
		public void onPendingUserPostsConsumed(Set<PendingUserPost> pendingUserPosts) {
			if (acceptResults.get() && pendingUserPosts != null && !pendingUserPosts.isEmpty()) {
				PostingService.consumePendingUserPosts(target.chanName, target.boardName,
						target.threadNumber, pendingUserPosts);
			}
		}

		@Override
		public void onReadPostsSuccess(PagesDatabase.Cache.State cacheState,
				List<PagesDatabase.InsertResult.Reply> replies, Integer newCount) {
			stats.succeeded.incrementAndGet();
			stats.rawReplies.addAndGet(replies.size());
			if (target.tracked) {
				replies = ReplyPushManager.filterPushNotifiedReplies(target.chanName,
						target.boardName, target.threadNumber, replies);
			}
			stats.filteredReplies.addAndGet(replies.size());
			boolean notificationsEnabled = (target.favoriteItem != null
					&& notificationFeatures.contains(Preferences.NotificationFeature.ENABLED))
					|| (target.tracked && Preferences.isTrackedRepliesNotificationsEnabled());
			boolean quietHours = Preferences.isReplyPushQuietHoursActive();
			notificationsEnabled &= !quietHours;
			if (acceptResults.get() && !replies.isEmpty() && notificationsEnabled) {
				String title = target.favoriteItem != null
						? StringUtils.emptyIfNull(target.favoriteItem.title) : "";
				if (title.trim().isEmpty()) {
					Chan chan = Chan.get(target.chanName);
					title = chan.configuration.getTitle() + " / " + target.boardName
							+ " / " + target.threadNumber;
				}
				boolean trackedNotification = target.tracked
						&& Preferences.isTrackedRepliesNotificationsEnabled();
				WatcherNotifications.notifyBackgroundReplies(context, notificationColor,
						trackedNotification
								|| notificationFeatures.contains(Preferences.NotificationFeature.IMPORTANT),
						trackedNotification
								|| notificationFeatures.contains(Preferences.NotificationFeature.SOUND),
						trackedNotification
								|| notificationFeatures.contains(Preferences.NotificationFeature.VIBRATION),
						title, target.chanName, target.boardName,
						target.threadNumber, replies);
				stats.queuedNotifications.addAndGet(replies.size());
				log("notifications_queued", "count", replies.size(), "tracked", target.tracked);
			} else if (!replies.isEmpty()) {
				stats.suppressedNotifications.addAndGet(replies.size());
				log("notifications_suppressed", "count", replies.size(), "accepted", acceptResults.get(),
						"enabled", notificationsEnabled, "quietHours", quietHours,
						"tracked", target.tracked);
			}
			finish();
		}

		@Override
		public void onReadPostsRedirect(RedirectException.Target redirectTarget) {
			stats.redirected.incrementAndGet();
			log("target_redirect", "accepted", acceptResults.get(), "tracked", target.tracked);
			if (acceptResults.get() && target.favoriteItem != null) {
				FavoritesStorage.getInstance().setWatcherEnabled(target.chanName,
						target.boardName, target.threadNumber, false);
			}
			finish();
		}

		@Override
		public void onReadPostsFail(ErrorItem errorItem) {
			stats.failed.incrementAndGet();
			logError("target_fail", "type", errorItem.type, "accepted", acceptResults.get(),
					"tracked", target.tracked);
			if (acceptResults.get() && target.favoriteItem != null
					&& errorItem.type == ErrorItem.Type.THREAD_NOT_EXISTS) {
				FavoritesStorage.getInstance().setWatcherEnabled(target.chanName,
						target.boardName, target.threadNumber, false);
			}
			finish();
		}

		private void finish() {
			if (finished.compareAndSet(false, true)) {
				latch.countDown();
			}
		}
	}
}
