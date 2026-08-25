package com.mishiranu.dashchan.content.push;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import chan.util.StringUtils;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.storage.MyPostsStorage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

public class ReplyPushSyncWorker extends Worker {
	private static final String UNIQUE_SYNC = "reply-push-sync";
	private static final String UNIQUE_DELAYED_SYNC = "reply-push-sync-delayed";
	private static final String UNIQUE_DELETE = "reply-push-delete";
	private static final String UNIQUE_RESET = "reply-push-reset";
	private static final String UNIQUE_PERIODIC = "reply-push-renew";
	private static final String KEY_MODE = "mode";
	private static final String KEY_INSTALLATION_ID = "installation_id";
	private static final String KEY_RETRY_ATTEMPT = "retry_attempt";
	private static final String MODE_SYNC = "sync";
	private static final String MODE_DELETE = "delete";
	private static final String MODE_RESET = "reset";
	private static final int MAX_WATCHES = 20;
	private static final int[] RETRY_DELAYS_SECONDS = {1, 2, 4, 8, 16, 32, 60};
	private static final Object RUN_LOCK = new Object();

	private static final class WatchTarget {
		public final MyPostsStorage.TrackedPost post;
		public final String watchId;

		private WatchTarget(MyPostsStorage.TrackedPost post, String watchId) {
			this.post = post;
			this.watchId = watchId;
		}
	}

	public ReplyPushSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
		super(context, workerParams);
	}

	public static void enqueueSync(Context context) {
		WorkManager workManager = WorkManager.getInstance(context.getApplicationContext());
		workManager.cancelUniqueWork(UNIQUE_DELAYED_SYNC);
		workManager.enqueueUniqueWork(UNIQUE_SYNC, ExistingWorkPolicy.APPEND_OR_REPLACE,
				newSyncRequest(0L, 0));
	}

	public static void updatePeriodicSchedule(Context context, boolean enabled) {
		WorkManager workManager = WorkManager.getInstance(context.getApplicationContext());
		if (enabled) {
			PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(ReplyPushSyncWorker.class,
					6L, TimeUnit.DAYS, 1L, TimeUnit.DAYS)
					.setInputData(new Data.Builder().putString(KEY_MODE, MODE_SYNC).build())
					.setConstraints(networkConstraints())
					.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
					.build();
			workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC,
					ExistingPeriodicWorkPolicy.KEEP, request);
		} else {
			workManager.cancelUniqueWork(UNIQUE_PERIODIC);
		}
	}

	public static void enqueueDelete(Context context, String installationId) {
		Context applicationContext = context.getApplicationContext();
		WorkManager workManager = WorkManager.getInstance(applicationContext);
		workManager.cancelUniqueWork(UNIQUE_SYNC);
		workManager.cancelUniqueWork(UNIQUE_DELAYED_SYNC);
		workManager.cancelUniqueWork(UNIQUE_PERIODIC);
		workManager.cancelUniqueWork(UNIQUE_RESET);
		Data data = new Data.Builder().putString(KEY_MODE, MODE_DELETE)
				.putString(KEY_INSTALLATION_ID, installationId).build();
		OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReplyPushSyncWorker.class)
				.setInputData(data)
				.setConstraints(networkConstraints())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
				.build();
		workManager.enqueueUniqueWork(UNIQUE_DELETE, ExistingWorkPolicy.REPLACE, request);
	}

	public static void enqueueIdentityReset(Context context, String installationId) {
		Context applicationContext = context.getApplicationContext();
		WorkManager workManager = WorkManager.getInstance(applicationContext);
		workManager.cancelUniqueWork(UNIQUE_SYNC);
		workManager.cancelUniqueWork(UNIQUE_DELAYED_SYNC);
		workManager.cancelUniqueWork(UNIQUE_PERIODIC);
		Data data = new Data.Builder().putString(KEY_MODE, MODE_RESET)
				.putString(KEY_INSTALLATION_ID, installationId).build();
		OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReplyPushSyncWorker.class)
				.setInputData(data)
				.setConstraints(networkConstraints())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
				.build();
		workManager.enqueueUniqueWork(UNIQUE_RESET, ExistingWorkPolicy.KEEP, request);
	}

	private static OneTimeWorkRequest newSyncRequest(long delaySeconds, int retryAttempt) {
		OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(ReplyPushSyncWorker.class)
				.setInputData(new Data.Builder().putString(KEY_MODE, MODE_SYNC)
						.putInt(KEY_RETRY_ATTEMPT, retryAttempt).build())
				.setConstraints(networkConstraints())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS);
		if (delaySeconds > 0L) {
			builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS);
		}
		return builder.build();
	}

	private static Constraints networkConstraints() {
		return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
	}

	@NonNull
	@Override
	public Result doWork() {
		synchronized (RUN_LOCK) {
			String mode = getInputData().getString(KEY_MODE);
			if (MODE_DELETE.equals(mode)) {
				return delete();
			}
			if (MODE_RESET.equals(mode)) {
				return resetIdentity();
			}
			return sync();
		}
	}

	private Result sync() {
		if (!Preferences.isTrackMyPostsEnabled() || !Preferences.isReplyPushEnabled()
				|| ReplyPushPrivateStore.isIdentityResetPending(getApplicationContext())) {
			return Result.success();
		}
		Context context = getApplicationContext();
		String installationId = ReplyPushPrivateStore.getInstallationId(context);
		if (!ReplyPushContract.isInstallationId(installationId)) {
			installationId = rotateInstallation(context, installationId);
		}
		try {
			ReplyPushFirebase.setAutoInitEnabled(context, true);
			ReplyPushFirebase.awaitRegistration(context);
		} catch (Exception e) {
			return retry(0);
		}
		String firebaseRegistrationId = ReplyPushPrivateStore.getFirebaseRegistrationId(context);
		if (StringUtils.isEmpty(firebaseRegistrationId)) {
			return retry(0);
		}

		ReplyPushBackendClient client = new ReplyPushBackendClient();
		for (int identityAttempt = 0; identityAttempt < 2; identityAttempt++) {
			String secret = ReplyPushPrivateStore.getSecret(context, installationId);
			ReplyPushBackendClient.Result registration = client.registerInstallation(
					installationId, firebaseRegistrationId, secret);
			if (registration.kind == ReplyPushBackendClient.Kind.TEMPORARY) {
				return retry(registration.retryAfterSeconds);
			}
			if (registration.kind == ReplyPushBackendClient.Kind.AUTH_INVALID
					|| registration.kind == ReplyPushBackendClient.Kind.CONFLICT) {
				installationId = rotateInstallation(context, installationId);
				continue;
			}
			if (registration.kind != ReplyPushBackendClient.Kind.SUCCESS) {
				updatePeriodicSchedule(context, false);
				return Result.failure();
			}
			if (registration.installationSecret != null) {
				ReplyPushPrivateStore.putSecret(context, installationId,
						registration.installationSecret);
				secret = registration.installationSecret;
			}
			if (StringUtils.isEmpty(secret)) {
				installationId = rotateInstallation(context, installationId);
				continue;
			}
			Result watchesResult = syncWatches(client, installationId, secret);
			if (watchesResult != null) {
				return watchesResult;
			}
			updatePeriodicSchedule(context, true);
			return Result.success();
		}
		return Result.failure();
	}

	private Result syncWatches(ReplyPushBackendClient client, String installationId, String secret) {
		List<MyPostsStorage.TrackedPost> posts = new ArrayList<>(MyPostsStorage.getInstance().getPosts());
		ArrayList<WatchTarget> targets = new ArrayList<>(MAX_WATCHES);
		for (MyPostsStorage.TrackedPost post : posts) {
			if (targets.size() >= MAX_WATCHES) {
				break;
			}
			String postNumber = post.postNumber.toString();
			if (!post.trackingActive || post.threadDeleted
					|| !ReplyPushContract.SUPPORTED_CHAN_NAME.equals(post.chanName)
					|| !ReplyPushContract.SUPPORTED_BOARD_NAME.equals(post.boardName)
					|| !ReplyPushContract.isCanonicalPositiveNumber(post.threadNumber)
					|| !ReplyPushContract.isCanonicalPositiveNumber(postNumber)) {
				continue;
			}
			String watchId = ReplyPushContract.makeWatchId(installationId, post.chanName,
					post.boardName, post.threadNumber, postNumber);
			if (watchId != null) {
				targets.add(new WatchTarget(post, watchId));
			}
		}

		Context context = getApplicationContext();
		Set<String> storedWatchIds = ReplyPushPrivateStore.getWatchIds(context, installationId);
		Set<String> storedRejectedWatchIds = ReplyPushPrivateStore.getRejectedWatchIds(context,
				installationId);
		HashSet<String> desiredWatchIds = new HashSet<>();
		for (WatchTarget target : targets) {
			desiredWatchIds.add(target.watchId);
		}
		HashSet<String> retainedWatchIds = new HashSet<>(storedWatchIds);
		HashSet<String> rejectedWatchIds = new HashSet<>(storedRejectedWatchIds);
		rejectedWatchIds.retainAll(desiredWatchIds);
		for (String watchId : storedWatchIds) {
			if (desiredWatchIds.contains(watchId)) {
				continue;
			}
			ReplyPushBackendClient.Result result = client.deleteWatch(installationId, secret, watchId);
			if (result.kind == ReplyPushBackendClient.Kind.TEMPORARY) {
				return retry(result.retryAfterSeconds);
			}
			if (result.kind == ReplyPushBackendClient.Kind.AUTH_INVALID
					|| result.kind == ReplyPushBackendClient.Kind.CONFLICT) {
				rotateInstallation(context, installationId);
				return retry(0);
			}
			if (result.kind == ReplyPushBackendClient.Kind.SUCCESS
					|| result.kind == ReplyPushBackendClient.Kind.PERMANENT) {
				retainedWatchIds.remove(watchId);
			}
		}

		for (WatchTarget target : targets) {
			if (rejectedWatchIds.contains(target.watchId)) {
				continue;
			}
			MyPostsStorage.TrackedPost post = target.post;
			ReplyPushBackendClient.Result result = client.putWatch(installationId, secret,
					post.chanName, post.boardName, post.threadNumber, post.postNumber.toString());
			if (result.kind == ReplyPushBackendClient.Kind.TEMPORARY) {
				return retry(result.retryAfterSeconds);
			}
			if (result.kind == ReplyPushBackendClient.Kind.AUTH_INVALID
					|| result.kind == ReplyPushBackendClient.Kind.CONFLICT) {
				rotateInstallation(context, installationId);
				return retry(0);
			}
			if (result.kind == ReplyPushBackendClient.Kind.SUCCESS) {
				retainedWatchIds.add(target.watchId);
			} else {
				retainedWatchIds.remove(target.watchId);
				if (result.kind == ReplyPushBackendClient.Kind.PERMANENT) {
					rejectedWatchIds.add(target.watchId);
				}
			}
		}
		retainedWatchIds.retainAll(desiredWatchIds);
		ReplyPushPrivateStore.setWatchIds(context, installationId, retainedWatchIds);
		ReplyPushPrivateStore.setRejectedWatchIds(context, installationId, rejectedWatchIds);
		return null;
	}

	private Result delete() {
		Context context = getApplicationContext();
		if (Preferences.isTrackMyPostsEnabled() && Preferences.isReplyPushEnabled()) {
			return Result.success();
		}
		String installationId = getInputData().getString(KEY_INSTALLATION_ID);
		String secret = ReplyPushPrivateStore.getSecret(context, installationId);
		if (ReplyPushContract.isInstallationId(installationId) && !StringUtils.isEmpty(secret)) {
			ReplyPushBackendClient.Result result = new ReplyPushBackendClient()
					.deleteInstallation(installationId, secret);
			if (result.kind == ReplyPushBackendClient.Kind.TEMPORARY) {
				return retryDelete(installationId, result.retryAfterSeconds);
			}
		}
		if (!ReplyPushFirebase.unregister(context)) {
			return retryDelete(installationId, 0);
		}
		ReplyPushPrivateStore.setFirebaseRegistrationId(context, null);
		ReplyPushPrivateStore.clearAll(context);
		return Result.success();
	}

	private Result resetIdentity() {
		Context context = getApplicationContext();
		MyPostsStorage storage = MyPostsStorage.getInstance();
		storage.deactivateAllTracking();
		storage.await(false);
		String installationId = getInputData().getString(KEY_INSTALLATION_ID);
		String secret = ReplyPushPrivateStore.getSecret(context, installationId);
		if (!ReplyPushContract.isInstallationId(installationId) || StringUtils.isEmpty(secret)) {
			ReplyPushPrivateStore.markIdentityResetFailed(context);
			return Result.failure();
		}
		if (!ReplyPushPrivateStore.isIdentityResetServerDeleted(context)) {
			ReplyPushBackendClient.Result deletion = new ReplyPushBackendClient()
					.deleteInstallation(installationId, secret);
			if (deletion.kind == ReplyPushBackendClient.Kind.TEMPORARY) {
				return retryIdentityReset(installationId, deletion.retryAfterSeconds);
			}
			if (deletion.kind != ReplyPushBackendClient.Kind.SUCCESS) {
				ReplyPushPrivateStore.markIdentityResetFailed(context);
				return Result.failure();
			}
			ReplyPushPrivateStore.markIdentityResetServerDeleted(context);
		}
		try {
			ReplyPushFirebase.setAutoInitEnabled(context, false);
		} catch (RuntimeException ignored) {
			// Token removal below remains authoritative and is retried before identity rotation.
		}
		if (!ReplyPushFirebase.unregister(context)) {
			return retryIdentityReset(installationId, 0);
		}
		ReplyPushPrivateStore.clearRegistration(context);
		Preferences.setReplyPushHandledEvents(new HashSet<>());
		Preferences.setReplyPushNotifiedReplies(new HashSet<>());
		if (Preferences.isTrackMyPostsEnabled() && Preferences.isReplyPushEnabled()) {
			ReplyPushPrivateStore.setInstallationId(context, ReplyPushContract.newInstallationId());
		} else {
			ReplyPushPrivateStore.setInstallationId(context, null);
		}
		ReplyPushPrivateStore.markIdentityResetSucceeded(context);
		return Preferences.isTrackMyPostsEnabled() && Preferences.isReplyPushEnabled()
				? sync() : Result.success();
	}

	private static String rotateInstallation(Context context, String previousId) {
		ReplyPushPrivateStore.clearInstallation(context, previousId);
		String installationId = ReplyPushContract.newInstallationId();
		ReplyPushPrivateStore.setInstallationId(context, installationId);
		return installationId;
	}

	private Result retry(int retryAfterSeconds) {
		int attempt = Math.max(0, getInputData().getInt(KEY_RETRY_ATTEMPT, 0));
		long delaySeconds = retryDelaySeconds(attempt, retryAfterSeconds);
		WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(UNIQUE_DELAYED_SYNC,
				ExistingWorkPolicy.REPLACE, newSyncRequest(delaySeconds, attempt + 1));
		return Result.success();
	}

	private Result retryDelete(String installationId, int retryAfterSeconds) {
		int attempt = Math.max(0, getInputData().getInt(KEY_RETRY_ATTEMPT, 0));
		long delaySeconds = retryDelaySeconds(attempt, retryAfterSeconds);
		Data data = new Data.Builder().putString(KEY_MODE, MODE_DELETE)
				.putString(KEY_INSTALLATION_ID, installationId)
				.putInt(KEY_RETRY_ATTEMPT, attempt + 1).build();
		OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReplyPushSyncWorker.class)
				.setInputData(data)
				.setConstraints(networkConstraints())
				.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
				.build();
		WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(UNIQUE_DELETE,
				ExistingWorkPolicy.REPLACE, request);
		return Result.success();
	}

	private Result retryIdentityReset(String installationId, int retryAfterSeconds) {
		int attempt = Math.max(0, getInputData().getInt(KEY_RETRY_ATTEMPT, 0));
		long delaySeconds = retryDelaySeconds(attempt, retryAfterSeconds);
		Data data = new Data.Builder().putString(KEY_MODE, MODE_RESET)
				.putString(KEY_INSTALLATION_ID, installationId)
				.putInt(KEY_RETRY_ATTEMPT, attempt + 1).build();
		OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReplyPushSyncWorker.class)
				.setInputData(data)
				.setConstraints(networkConstraints())
				.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
				.build();
		WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(UNIQUE_RESET,
				ExistingWorkPolicy.REPLACE, request);
		return Result.success();
	}

	private static long retryDelaySeconds(int attempt, int retryAfterSeconds) {
		int index = Math.min(attempt, RETRY_DELAYS_SECONDS.length - 1);
		return withJitter(Math.max(RETRY_DELAYS_SECONDS[index], retryAfterSeconds));
	}

	private static long withJitter(int seconds) {
		long maximumJitter = seconds / 4L;
		return maximumJitter > 0L
				? seconds + ThreadLocalRandom.current().nextLong(maximumJitter + 1L) : seconds;
	}
}
