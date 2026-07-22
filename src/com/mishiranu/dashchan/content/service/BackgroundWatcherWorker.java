package com.mishiranu.dashchan.content.service;

import android.app.ActivityManager;
import android.content.Context;
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
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.WatcherNotifications;
import com.mishiranu.dashchan.content.async.ReadPostsTask;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.PendingUserPost;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BackgroundWatcherWorker extends Worker {
	private static final String UNIQUE_WORK_NAME = "background-reply-check";
	private static final long INTERVAL_MINUTES = 15;
	private static final long MAX_RUN_MINUTES = 8;

	private final List<ReadPostsTask> tasks = Collections.synchronizedList(new ArrayList<>());
	private volatile ExecutorService executor;

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
		if (Preferences.isBackgroundReplyCheckEnabled()) {
			NetworkType networkType = Preferences.isWatcherWifiOnly()
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

	@NonNull
	@Override
	public Result doWork() {
		if (!Preferences.isBackgroundReplyCheckEnabled() || isApplicationVisible()) {
			return Result.success();
		}

		List<FavoritesStorage.FavoriteItem> favorites = ConcurrentUtils.mainGet(() -> {
			ArrayList<FavoritesStorage.FavoriteItem> result = new ArrayList<>();
			for (FavoritesStorage.FavoriteItem favoriteItem : FavoritesStorage.getInstance().getThreads(null)) {
				if (favoriteItem.watcherEnabled) {
					result.add(new FavoritesStorage.FavoriteItem(favoriteItem));
				}
			}
			return result;
		});
		favorites.removeIf(favoriteItem -> {
			Chan chan = Chan.get(favoriteItem.chanName);
			return chan.name == null || chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE);
		});
		if (favorites.isEmpty()) {
			return Result.success();
		}

		Context context = getApplicationContext();
		WatcherNotifications.configure(context);
		int notificationColor = ConcurrentUtils.mainGet(() -> ThemeEngine.attachAndApply(context).accent);
		Set<Preferences.NotificationFeature> notificationFeatures = Preferences.getWatcherNotifications();
		CountDownLatch latch = new CountDownLatch(favorites.size());
		executor = ConcurrentUtils.newThreadPool(3, 3, 0, "BackgroundWatcher", null);

		for (FavoritesStorage.FavoriteItem favoriteItem : favorites) {
			Set<PendingUserPost> pendingUserPosts = ConcurrentUtils.mainGet(() -> {
				Set<PendingUserPost> pending = PostingService.getPendingUserPosts(favoriteItem.chanName,
						favoriteItem.boardName, favoriteItem.threadNumber);
				return pending != null ? new HashSet<>(pending) : null;
			});
			ReadPostsTask.Callback callback = new Callback(context, favoriteItem, notificationColor,
					notificationFeatures, latch);
			ReadPostsTask task = new ReadPostsTask(callback, Chan.get(favoriteItem.chanName),
					favoriteItem.boardName, favoriteItem.threadNumber, false, pendingUserPosts);
			tasks.add(task);
			try {
				task.execute(executor);
			} catch (RuntimeException e) {
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
			return Result.retry();
		} finally {
			ExecutorService executor = this.executor;
			if (executor != null) {
				executor.shutdownNow();
			}
			this.executor = null;
		}
		if (!complete) {
			cancelTasks();
			return Result.retry();
		}
		return Result.success();
	}

	@Override
	public void onStopped() {
		super.onStopped();
		cancelTasks();
		ExecutorService executor = this.executor;
		if (executor != null) {
			executor.shutdownNow();
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
		private final FavoritesStorage.FavoriteItem favoriteItem;
		private final int notificationColor;
		private final Set<Preferences.NotificationFeature> notificationFeatures;
		private final CountDownLatch latch;
		private final AtomicBoolean finished = new AtomicBoolean();

		public Callback(Context context, FavoritesStorage.FavoriteItem favoriteItem, int notificationColor,
				Set<Preferences.NotificationFeature> notificationFeatures, CountDownLatch latch) {
			this.context = context;
			this.favoriteItem = favoriteItem;
			this.notificationColor = notificationColor;
			this.notificationFeatures = notificationFeatures;
			this.latch = latch;
		}

		@Override
		public void onPendingUserPostsConsumed(Set<PendingUserPost> pendingUserPosts) {
			if (pendingUserPosts != null && !pendingUserPosts.isEmpty()) {
				PostingService.consumePendingUserPosts(favoriteItem.chanName, favoriteItem.boardName,
						favoriteItem.threadNumber, pendingUserPosts);
			}
		}

		@Override
		public void onReadPostsSuccess(PagesDatabase.Cache.State cacheState,
				List<PagesDatabase.InsertResult.Reply> replies, Integer newCount) {
			if (!replies.isEmpty() && notificationFeatures.contains(Preferences.NotificationFeature.ENABLED)) {
				String title = StringUtils.emptyIfNull(favoriteItem.title);
				if (title.trim().isEmpty()) {
					Chan chan = Chan.get(favoriteItem.chanName);
					title = chan.configuration.getTitle() + " / " + favoriteItem.boardName
							+ " / " + favoriteItem.threadNumber;
				}
				WatcherNotifications.notifyReplies(context, notificationColor,
						notificationFeatures.contains(Preferences.NotificationFeature.IMPORTANT),
						notificationFeatures.contains(Preferences.NotificationFeature.SOUND),
						notificationFeatures.contains(Preferences.NotificationFeature.VIBRATION),
						title, favoriteItem.chanName, favoriteItem.boardName,
						favoriteItem.threadNumber, replies);
			}
			finish();
		}

		@Override
		public void onReadPostsRedirect(RedirectException.Target target) {
			FavoritesStorage.getInstance().setWatcherEnabled(favoriteItem.chanName,
					favoriteItem.boardName, favoriteItem.threadNumber, false);
			finish();
		}

		@Override
		public void onReadPostsFail(ErrorItem errorItem) {
			if (errorItem.type == ErrorItem.Type.THREAD_NOT_EXISTS) {
				FavoritesStorage.getInstance().setWatcherEnabled(favoriteItem.chanName,
						favoriteItem.boardName, favoriteItem.threadNumber, false);
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
