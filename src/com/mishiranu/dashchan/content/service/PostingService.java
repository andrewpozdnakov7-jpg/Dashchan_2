package com.mishiranu.dashchan.content.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PowerManager;
import android.util.Pair;
import androidx.core.app.NotificationCompat;
import chan.content.ApiException;
import chan.content.Chan;
import chan.content.ChanPerformer;
import chan.text.CommentEditor;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.LocaleManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.SendPostTask;
import com.mishiranu.dashchan.content.database.ChanDatabase;
import com.mishiranu.dashchan.content.database.CommonDatabase;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.PendingUserPost;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.content.storage.StatisticsStorage;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.Hasher;
import com.mishiranu.dashchan.util.WeakObservable;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

public class PostingService extends BaseService implements SendPostTask.Callback<PostingService.Key> {
	private static final String ACTION_CANCEL = "cancel";
	private static final long FLOOD_RETRY_DELAY = 5000L;
	private static final int MAX_FLOOD_RETRIES = 12;

	private final HashMap<Key, ArrayList<Callback>> callbacks = new HashMap<>();
	private final WeakObservable<GlobalCallback> globalCallbacks = new WeakObservable<>();
	private final HashMap<Callback, Key> callbackKeys = new HashMap<>();
	private final ArrayDeque<QueueItem> postQueue = new ArrayDeque<>();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private Runnable retryRunnable;
	private TaskState taskState;

	private NotificationManager notificationManager;
	private int notificationColor;
	private PowerManager.WakeLock wakeLock;

	private Thread notificationsWorker;
	private final LinkedBlockingQueue<NotificationData> notificationsQueue = new LinkedBlockingQueue<>();

	public static final class Key {
		public final String chanName;
		public final String boardName;
		public final String threadNumber;

		private Key(String chanName, String boardName, String threadNumber) {
			this.chanName = chanName;
			this.boardName = boardName;
			this.threadNumber = threadNumber;
		}

		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			if (o instanceof Key) {
				Key key = (Key) o;
				return CommonUtils.equals(key.chanName, chanName) &&
						CommonUtils.equals(key.boardName, boardName) &&
						CommonUtils.equals(key.threadNumber, threadNumber);
			}
			return false;
		}

		@Override
		public int hashCode() {
			int result = chanName != null ? chanName.hashCode() : 0;
			result = 31 * result + (boardName != null ? boardName.hashCode() : 0);
			result = 31 * result + (threadNumber != null ? threadNumber.hashCode() : 0);
			return result;
		}
	}

	private static class QueueItem {
		public final Key key;
		public final String chanName;
		public final ChanPerformer.SendPostData data;
		public final DraftsStorage.PostDraft postDraft;
		public final ArrayList<String> attachmentHashes;
		public final boolean allowFloodRetry;
		public int floodRetryCount;

		public QueueItem(String chanName, ChanPerformer.SendPostData data,
				DraftsStorage.PostDraft postDraft, Collection<String> attachmentHashes,
				boolean allowFloodRetry) {
			this.key = new Key(chanName, data.boardName, data.threadNumber);
			this.chanName = chanName;
			this.data = data;
			this.postDraft = postDraft;
			this.attachmentHashes = new ArrayList<>(attachmentHashes);
			this.allowFloodRetry = allowFloodRetry;
		}
	}

	private static class TaskState {
		public final QueueItem queueItem;
		public final Key key;
		public SendPostTask<Key> task;
		public final NotificationCompat.Builder builder;
		public final String text;
		public boolean waitingForRetry;

		private SendPostTask.ProgressState progressState = SendPostTask.ProgressState.CONNECTING;
		private int attachmentIndex = 0;
		private int attachmentsCount = 0;

		private long progress = 0;
		private long progressMax = 0;

		public TaskState(QueueItem queueItem, SendPostTask<Key> task, Context context, Chan chan) {
			this.queueItem = queueItem;
			this.key = queueItem.key;
			this.task = task;
			builder = new NotificationCompat.Builder(context, C.NOTIFICATION_CHANNEL_POSTING);
			text = buildNotificationText(chan, queueItem.data.boardName, queueItem.data.threadNumber, null);
		}

		public void reset(SendPostTask<Key> task) {
			this.task = task;
			waitingForRetry = false;
			progressState = SendPostTask.ProgressState.CONNECTING;
			attachmentIndex = 0;
			attachmentsCount = 0;
			progress = 0;
			progressMax = 0;
		}
	}

	private static class NotificationData {
		public enum Type {CREATE, UPDATE, CANCEL}

		public final Type type;
		public final TaskState taskState;
		public final CountDownLatch syncLatch;

		private NotificationData(Type type, TaskState taskState, CountDownLatch syncLatch) {
			this.type = type;
			this.taskState = taskState;
			this.syncLatch = syncLatch;
		}
	}

	private static final Set<DraftsStorage.PostDraft> QUEUED_POST_DRAFTS = Collections
			.newSetFromMap(new IdentityHashMap<>());
	private static final HashMap<Key, ArrayDeque<QueueItem>> FAILED_POSTS = new HashMap<>();

	public static synchronized boolean isPostDraftQueued(DraftsStorage.PostDraft postDraft) {
		return postDraft != null && QUEUED_POST_DRAFTS.contains(postDraft);
	}

	public static synchronized DraftsStorage.PostDraft restoreFailedPostDraft(String chanName,
			String boardName, String threadNumber) {
		Key key = new Key(chanName, boardName, threadNumber);
		ArrayDeque<QueueItem> queueItems = FAILED_POSTS.get(key);
		QueueItem queueItem = queueItems != null ? queueItems.pollFirst() : null;
		if (queueItems != null && queueItems.isEmpty()) {
			FAILED_POSTS.remove(key);
		}
		if (queueItem != null) {
			DraftsStorage draftsStorage = DraftsStorage.getInstance();
			draftsStorage.store(queueItem.postDraft);
			draftsStorage.releaseAttachmentDrafts(queueItem.attachmentHashes);
			return queueItem.postDraft;
		}
		return null;
	}

	private static synchronized void markPostDraftQueued(QueueItem queueItem) {
		QUEUED_POST_DRAFTS.add(queueItem.postDraft);
	}

	private static synchronized void unmarkPostDraftQueued(QueueItem queueItem) {
		QUEUED_POST_DRAFTS.remove(queueItem.postDraft);
	}

	private static synchronized void storeFailedPost(QueueItem queueItem) {
		unmarkPostDraftQueued(queueItem);
		DraftsStorage draftsStorage = DraftsStorage.getInstance();
		if (draftsStorage.getPostDraft(queueItem.key.chanName, queueItem.key.boardName,
				queueItem.key.threadNumber) == queueItem.postDraft) {
			draftsStorage.releaseAttachmentDrafts(queueItem.attachmentHashes);
			return;
		}
		ArrayDeque<QueueItem> queueItems = FAILED_POSTS.get(queueItem.key);
		if (queueItems == null) {
			queueItems = new ArrayDeque<>();
			FAILED_POSTS.put(queueItem.key, queueItems);
		}
		queueItems.addLast(queueItem);
	}

	public static String buildNotificationText(Chan chan, String boardName, String threadNumber,
			PostNumber postNumber) {
		StringBuilder builder = new StringBuilder(chan.configuration.getTitle()).append(", ");
		builder.append(StringUtils.formatThreadTitle(chan.name,
				boardName, threadNumber != null ? threadNumber : "?"));
		if (postNumber != null) {
			builder.append(", #").append(postNumber);
		}
		return builder.toString();
	}

	@Override
	protected void attachBaseContext(Context newBase) {
		super.attachBaseContext(LocaleManager.getInstance().apply(newBase));
	}

	@Override
	public void onCreate() {
		super.onCreate();
		notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		ThemeEngine.Theme theme = ThemeEngine.attachAndApply(this);
		int notificationColor = theme.accent;
		this.notificationColor = notificationColor;
		notificationManager.createNotificationChannel
				(new NotificationChannel(C.NOTIFICATION_CHANNEL_POSTING,
						getString(R.string.posting), NotificationManager.IMPORTANCE_LOW));
		notificationManager.createNotificationChannel(AndroidUtils
				.createHeadsUpNotificationChannel(C.NOTIFICATION_CHANNEL_POSTING_COMPLETE,
						getString(R.string.sent_posts)));
		PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":PostingWakeLock");
		wakeLock.setReferenceCounted(false);
		addOnDestroyListener(ChanDatabase.getInstance().requireCookies());
		notificationsWorker = new Thread(notificationsRunnable, "PostingServiceNotificationThread");
		notificationsWorker.start();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		performFinish(null, true);
		if (wakeLock.isHeld()) {
			wakeLock.release();
		}
		// Ensure queue is empty
		refreshNotification(NotificationData.Type.CANCEL, null);
		notificationsWorker.interrupt();
		try {
			notificationsWorker.join();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_NOT_STICKY;
	}

	private final Runnable notificationsRunnable = () -> {
		boolean interrupted = false;
		while (true) {
			NotificationData notificationData = null;
			if (!interrupted) {
				try {
					notificationData = notificationsQueue.take();
				} catch (InterruptedException e) {
					interrupted = true;
				}
			}
			if (interrupted) {
				notificationData = notificationsQueue.poll();
			}
			if (notificationData == null) {
				return;
			}
			if (notificationData.type == NotificationData.Type.CANCEL) {
				AndroidUtils.stopForegroundRemove(this);
				stopSelf();
			} else {
				TaskState taskState = notificationData.taskState;
				NotificationCompat.Builder builder = taskState.builder;
				if (notificationData.type == NotificationData.Type.CREATE) {
					builder.setSmallIcon(android.R.drawable.stat_sys_upload);
					PendingIntent cancelIntent = PendingIntent.getBroadcast(this, 0, new Intent(this, Receiver.class)
							.setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT);
					builder.addAction(0, getString(android.R.string.cancel), cancelIntent);
					builder.setColor(notificationColor);
					AndroidUtils.startAnyService(this, new Intent(this, PostingService.class));
				}
				boolean progressMode = taskState.task.isProgressMode();
				builder.setProgress(0, 0, false);
				if (taskState.waitingForRetry) {
					builder.setProgress(0, 0, true);
					builder.setContentTitle(getString(R.string.post_waiting_to_send));
				} else switch (taskState.progressState) {
					case CONNECTING: {
						if (progressMode) {
							builder.setProgress(1, 0, true);
						}
						builder.setContentTitle(getString(R.string.sending__ellipsis));
						break;
					}
					case SENDING: {
						if (progressMode) {
							if (taskState.progressMax > 0) {
								int max = 1000;
								int progress = (int) (taskState.progress * max / taskState.progressMax);
								builder.setProgress(max, progress, false);
							} else {
								builder.setProgress(0, 0, true);
							}
							builder.setContentTitle(getString(R.string.sending_number_of_number__ellipsis_format,
									taskState.attachmentIndex + 1, taskState.attachmentsCount));
						} else {
							builder.setContentTitle(getString(R.string.sending__ellipsis));
						}
						break;
					}
					case PROCESSING: {
						if (progressMode) {
							builder.setProgress(1, 1, false);
						}
						builder.setContentTitle(getString(R.string.processing_data__ellipsis));
						break;
					}
				}
				builder.setContentText(taskState.text);
				startForeground(C.NOTIFICATION_ID_POSTING, builder.build());
			}
			if (notificationData.syncLatch != null) {
				notificationData.syncLatch.countDown();
			}
		}
	};

	@Override
	public Binder onBind(Intent intent) {
		return new Binder();
	}

	public interface Callback {
		void onState(boolean progressMode, SendPostTask.ProgressState progressState,
				int attachmentIndex, int attachmentsCount);
		void onProgress(long progress, long progressMax);
		void onStop(boolean success);
	}

	public interface GlobalCallback {
		void onPostSent();
	}

	public class Binder extends android.os.Binder {
		public boolean executeSendPost(String chanName, ChanPerformer.SendPostData data,
				DraftsStorage.PostDraft postDraft, Collection<String> attachmentHashes,
				boolean allowFloodRetry) {
			if (!postQueue.isEmpty() && !allowFloodRetry) {
				return false;
			}
			QueueItem queueItem = new QueueItem(chanName, data, postDraft, attachmentHashes, allowFloodRetry);
			DraftsStorage.getInstance().retainAttachmentDrafts(queueItem.attachmentHashes);
			markPostDraftQueued(queueItem);
			boolean start = postQueue.isEmpty();
			postQueue.addLast(queueItem);
			if (start) {
				AndroidUtils.startAnyService(PostingService.this,
						new Intent(PostingService.this, PostingService.class));
				if (!wakeLock.isHeld()) {
					wakeLock.acquire();
				}
				startCurrentPost();
			}
			return true;
		}

		public void cancelSendPost(String chanName, String boardName, String threadNumber) {
			performFinish(new Key(chanName, boardName, threadNumber), true);
		}

		private void cancelCurrentSendPost() {
			performFinish(null, true);
		}

		public void register(Callback callback, String chanName, String boardName, String threadNumber) {
			Key key = new Key(chanName, boardName, threadNumber);
			callbackKeys.put(callback, key);
			ArrayList<Callback> callbacks = PostingService.this.callbacks.get(key);
			if (callbacks == null) {
				callbacks = new ArrayList<>(1);
				PostingService.this.callbacks.put(key, callbacks);
			}
			callbacks.add(callback);
			if (taskState != null && !taskState.queueItem.allowFloodRetry && taskState.key.equals(key)) {
				notifyInit(callback, taskState);
			}
		}

		public void unregister(Callback callback) {
			Key key = callbackKeys.remove(callback);
			if (key != null) {
				ArrayList<Callback> callbacks = PostingService.this.callbacks.get(key);
				callbacks.remove(callback);
				if (callbacks.isEmpty()) {
					PostingService.this.callbacks.remove(key);
				}
			}
		}

		public void register(GlobalCallback globalCallback) {
			globalCallbacks.register(globalCallback);
		}

		public void unregister(GlobalCallback globalCallback) {
			globalCallbacks.unregister(globalCallback);
		}
	}

	private void refreshNotification(NotificationData.Type type, TaskState taskState) {
		CountDownLatch syncLatch = type == NotificationData.Type.CREATE || type == NotificationData.Type.CANCEL
				? new CountDownLatch(1) : null;
		notificationsQueue.add(new NotificationData(type, taskState, syncLatch));
		if (syncLatch != null) {
			try {
				syncLatch.await();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private void startCurrentPost() {
		QueueItem queueItem = postQueue.peekFirst();
		if (queueItem == null) {
			return;
		}
		Chan chan = Chan.get(queueItem.chanName);
		SendPostTask<Key> task = new SendPostTask<>(queueItem.key, this, chan, queueItem.data);
		TaskState taskState = this.taskState;
		if (taskState == null || taskState.queueItem != queueItem) {
			taskState = new TaskState(queueItem, task, this, chan);
			this.taskState = taskState;
			refreshNotification(NotificationData.Type.CREATE, taskState);
		} else {
			taskState.reset(task);
			refreshNotification(NotificationData.Type.UPDATE, taskState);
		}
		task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
		if (!queueItem.allowFloodRetry) {
			ArrayList<Callback> callbacks = this.callbacks.get(queueItem.key);
			if (callbacks != null) {
				for (Callback callback : callbacks) {
					notifyInit(callback, taskState);
				}
			}
		}
	}

	private QueueItem takeCurrentPost(Key key) {
		TaskState taskState = this.taskState;
		QueueItem queueItem = postQueue.peekFirst();
		if (taskState != null && queueItem != null && taskState.queueItem == queueItem
				&& taskState.key.equals(key)) {
			if (retryRunnable != null) {
				handler.removeCallbacks(retryRunnable);
				retryRunnable = null;
			}
			this.taskState = null;
			postQueue.removeFirst();
			return queueItem;
		}
		return null;
	}

	private void releaseQueueItem(QueueItem queueItem) {
		unmarkPostDraftQueued(queueItem);
		DraftsStorage.getInstance().releaseAttachmentDrafts(queueItem.attachmentHashes);
	}

	private void advanceQueue() {
		if (!postQueue.isEmpty()) {
			startCurrentPost();
		} else {
			refreshNotification(NotificationData.Type.CANCEL, null);
			if (wakeLock.isHeld()) {
				wakeLock.release();
			}
		}
	}

	private void notifyInit(Callback callback, TaskState taskState) {
		boolean progressMode = taskState.task.isProgressMode();
		callback.onState(progressMode, taskState.progressState, taskState.attachmentIndex,
				taskState.attachmentsCount);
		callback.onProgress(taskState.progress, taskState.progressMax);
	}

	private boolean performFinish(Key key, boolean cancel) {
		TaskState taskState = this.taskState;
		if (taskState != null && (key == null || taskState.key.equals(key))) {
			if (retryRunnable != null) {
				handler.removeCallbacks(retryRunnable);
				retryRunnable = null;
			}
			this.taskState = null;
			if (cancel) {
				taskState.task.cancel();
			}
			ArrayList<QueueItem> cancelledItems = new ArrayList<>(postQueue);
			postQueue.clear();
			for (QueueItem queueItem : cancelledItems) {
				if (queueItem.allowFloodRetry) {
					storeFailedPost(queueItem);
				} else {
					releaseQueueItem(queueItem);
				}
			}
			refreshNotification(NotificationData.Type.CANCEL, null);
			if (wakeLock.isHeld()) {
				wakeLock.release();
			}
			if (cancel && !taskState.queueItem.allowFloodRetry) {
				ArrayList<Callback> callbacks = this.callbacks.get(taskState.key);
				if (callbacks != null) {
					for (Callback callback : callbacks) {
						callback.onStop(false);
					}
				}
			}
			return true;
		}
		return false;
	}

	@Override
	public void onSendPostChangeProgressState(Key key, SendPostTask.ProgressState progressState,
			int attachmentIndex, int attachmentsCount) {
		TaskState taskState = this.taskState;
		if (taskState != null && taskState.key.equals(key)) {
			taskState.progressState = progressState;
			taskState.attachmentIndex = attachmentIndex;
			taskState.attachmentsCount = attachmentsCount;
			refreshNotification(NotificationData.Type.UPDATE, taskState);
			ArrayList<Callback> callbacks = this.callbacks.get(key);
			if (!taskState.queueItem.allowFloodRetry && callbacks != null) {
				boolean progressMode = taskState.task.isProgressMode();
				for (Callback callback : callbacks) {
					callback.onState(progressMode, progressState, attachmentIndex, attachmentsCount);
				}
			}
		}
	}

	@Override
	public void onSendPostChangeProgressValue(Key key, long progress, long progressMax) {
		TaskState taskState = this.taskState;
		if (taskState != null && taskState.key.equals(key)) {
			taskState.progress = progress;
			taskState.progressMax = progressMax;
			refreshNotification(NotificationData.Type.UPDATE, taskState);
			ArrayList<Callback> callbacks = this.callbacks.get(key);
			if (!taskState.queueItem.allowFloodRetry && callbacks != null) {
				for (Callback callback : callbacks) {
					callback.onProgress(progress, progressMax);
				}
			}
		}
	}

	@Override
	public void onSendPostSuccess(Key key, ChanPerformer.SendPostData data,
			String chanName, String threadNumber, PostNumber postNumber) {
		QueueItem queueItem = takeCurrentPost(key);
		if (queueItem != null) {
			Chan chan = Chan.get(chanName);
			String targetThreadNumber = data.threadNumber != null ? data.threadNumber
					: StringUtils.nullIfEmpty(threadNumber);
			DraftsStorage draftsStorage = DraftsStorage.getInstance();
			if (!queueItem.allowFloodRetry) {
				draftsStorage.removeCaptchaDraft();
			}
			draftsStorage.removePostDraft(queueItem.postDraft);
			if (targetThreadNumber != null && draftsStorage
					.getPostDraft(chanName, data.boardName, targetThreadNumber) == null) {
				String password = Preferences.getPassword(chan);
				if (CommonUtils.equals(password, data.password)) {
					password = null;
				}
				draftsStorage.store(new DraftsStorage.PostDraft(chanName, data.boardName, targetThreadNumber,
						data.name, data.email, password, data.optionSage, data.optionOriginalPoster, data.userIcon));
			}

			if (targetThreadNumber != null) {
				String comment = data.comment;
				if (comment != null) {
					CommentEditor commentEditor = chan.markup.safe().obtainCommentEditor(data.boardName);
					if (commentEditor != null) {
						comment = commentEditor.removeTags(comment);
					}
				}
				Key arrayKey = new Key(chanName, data.boardName, targetThreadNumber);
				boolean newThread = data.threadNumber == null;

				PendingUserPost pendingUserPost = null;
				if (postNumber != null) {
					CommonDatabase.getInstance().getPosts().setFlags(true, chanName, data.boardName,
							targetThreadNumber, postNumber, PostItem.HideState.UNDEFINED, true);
				} else if (newThread) {
					pendingUserPost = PendingUserPost.NewThread.INSTANCE;
				} else {
					pendingUserPost = new PendingUserPost.SimilarComment(comment, System.currentTimeMillis());
				}
				if (pendingUserPost != null) {
					HashSet<PendingUserPost> pendingUserPosts = PENDING_USER_POST_MAP.get(arrayKey);
					if (pendingUserPosts == null) {
						pendingUserPosts = new HashSet<>(1);
						PENDING_USER_POST_MAP.put(arrayKey, pendingUserPosts);
					}
					pendingUserPosts.add(pendingUserPost);
				}

				NewPostData newPostData = new NewPostData(arrayKey, postNumber, comment, newThread);
				ArrayList<NewPostData> newPostDataList = NEW_POST_DATA_MAP.get(arrayKey);
				if (newPostDataList == null) {
					newPostDataList = new ArrayList<>(1);
					NEW_POST_DATA_MAP.put(arrayKey, newPostDataList);
				}
				newPostDataList.add(newPostData);
				if (newThread) {
					PostingService.newThreadData = new Pair<>(new Key(chanName, data.boardName, null), newPostData);
				}

				NotificationCompat.Builder builder = new NotificationCompat.Builder(this,
						C.NOTIFICATION_CHANNEL_POSTING_COMPLETE);
				builder.setSmallIcon(android.R.drawable.stat_sys_upload_done);
				builder.setColor(notificationColor);
				builder.setPriority(NotificationCompat.PRIORITY_HIGH);
				builder.setVibrate(new long[0]);
				builder.setContentTitle(getString(R.string.post_sent));
				builder.setContentText(buildNotificationText(chan, data.boardName, targetThreadNumber, postNumber));
				String tag = newPostData.tag;
				Intent intent = new Intent(this, MainActivity.class).setAction(tag)
						.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
						.putExtra(C.EXTRA_CHAN_NAME, chanName)
						.putExtra(C.EXTRA_BOARD_NAME, data.boardName)
						.putExtra(C.EXTRA_THREAD_NUMBER, targetThreadNumber)
						.putExtra(C.EXTRA_POST_NUMBER, postNumber != null ? postNumber.toString() : null);
				builder.setContentIntent(PendingIntent.getActivity(this, 0, intent,
						PendingIntent.FLAG_UPDATE_CURRENT));
				notificationManager.notify(tag, 0, builder.build());
			}

			if (targetThreadNumber != null && Preferences.getFavoriteOnReply().isEnabled(data.optionSage)) {
				// Add to favorites after processing the response to ensure watcher is not triggered too early
				FavoritesStorage.getInstance().add(chanName, data.boardName, targetThreadNumber, null, true);
			}
			StatisticsStorage.getInstance().incrementPostsSent(chanName, data.threadNumber == null);
			ArrayList<Callback> callbacks = this.callbacks.get(key);
			if (!queueItem.allowFloodRetry && callbacks != null) {
				for (Callback callback : callbacks) {
					callback.onStop(true);
				}
			}
			for (GlobalCallback globalCallback : globalCallbacks) {
				globalCallback.onPostSent();
			}
			releaseQueueItem(queueItem);
			advanceQueue();
		}
	}

	@Override
	public void onSendPostFail(Key key, ChanPerformer.SendPostData data, String chanName, ErrorItem errorItem,
			ApiException.Extra extra, boolean captchaError, boolean keepCaptcha) {
		TaskState taskState = this.taskState;
		QueueItem currentItem = postQueue.peekFirst();
		// Transport errors are not retried because the server may have accepted the post before disconnecting.
		boolean tooFast = errorItem.type == ErrorItem.Type.API
				&& errorItem.specialType == ApiException.SEND_ERROR_TOO_FAST;
		if (taskState != null && currentItem != null && taskState.queueItem == currentItem
				&& taskState.key.equals(key) && currentItem.allowFloodRetry && tooFast
				&& currentItem.floodRetryCount < MAX_FLOOD_RETRIES) {
			currentItem.floodRetryCount++;
			taskState.waitingForRetry = true;
			refreshNotification(NotificationData.Type.UPDATE, taskState);
			retryRunnable = () -> {
				retryRunnable = null;
				if (this.taskState == taskState && postQueue.peekFirst() == currentItem) {
					startCurrentPost();
				}
			};
			handler.postDelayed(retryRunnable, FLOOD_RETRY_DELAY);
			return;
		}

		QueueItem queueItem = takeCurrentPost(key);
		if (queueItem != null) {
			FailResult failResult = new FailResult(errorItem, extra, captchaError, keepCaptcha);
			if (queueItem.allowFloodRetry) {
				storeFailedPost(queueItem);
				showPostFailedNotification(queueItem, failResult);
			} else {
				releaseQueueItem(queueItem);
			}
			ArrayList<Callback> callbacks = this.callbacks.get(key);
			if (!queueItem.allowFloodRetry && callbacks != null) {
				for (Callback callback : callbacks) {
					callback.onStop(false);
				}
			}
			advanceQueue();
			if (!queueItem.allowFloodRetry) {
				startActivity(new Intent(this, MainActivity.class).setAction(C.ACTION_POSTING)
						.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(C.EXTRA_CHAN_NAME, chanName)
						.putExtra(C.EXTRA_BOARD_NAME, data.boardName)
						.putExtra(C.EXTRA_THREAD_NUMBER, data.threadNumber)
						.putExtra(C.EXTRA_FAIL_RESULT, failResult));
			}
		}
	}

	private void showPostFailedNotification(QueueItem queueItem, FailResult failResult) {
		Chan chan = Chan.get(queueItem.chanName);
		String details = buildNotificationText(chan, queueItem.data.boardName, queueItem.data.threadNumber, null)
				+ ": " + failResult.errorItem;
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this,
				C.NOTIFICATION_CHANNEL_POSTING_COMPLETE);
		builder.setSmallIcon(android.R.drawable.stat_notify_error);
		builder.setColor(notificationColor);
		builder.setPriority(NotificationCompat.PRIORITY_HIGH);
		builder.setVibrate(new long[0]);
		builder.setContentTitle(getString(R.string.post_not_sent));
		builder.setContentText(details);
		builder.setStyle(new NotificationCompat.BigTextStyle().bigText(details));
		Intent intent = new Intent(this, MainActivity.class).setAction(C.ACTION_POSTING)
				.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
				.putExtra(C.EXTRA_CHAN_NAME, queueItem.chanName)
				.putExtra(C.EXTRA_BOARD_NAME, queueItem.data.boardName)
				.putExtra(C.EXTRA_THREAD_NUMBER, queueItem.data.threadNumber)
				.putExtra(C.EXTRA_FAIL_RESULT, failResult);
		String tag = "posting-fail:" + StringUtils.formatHex(Hasher.getInstanceSha256().calculate(
				queueItem.chanName + "/" + queueItem.data.boardName + "/" + queueItem.data.threadNumber + "/"
						+ System.nanoTime()));
		builder.setContentIntent(PendingIntent.getActivity(this, tag.hashCode(), intent,
				PendingIntent.FLAG_UPDATE_CURRENT));
		notificationManager.notify(tag, 0, builder.build());
	}

	public static class Receiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent != null ? intent.getAction() : null;
			boolean cancel = ACTION_CANCEL.equals(action);
			Context bindContext = context.getApplicationContext();
			if (cancel) {
				// Broadcast receivers can't bind to services
				ServiceConnection[] connection = {null};
				connection[0] = new ServiceConnection() {
					@Override
					public void onServiceConnected(ComponentName componentName, IBinder binder) {
						Binder postingBinder = (Binder) binder;
						if (cancel) {
							postingBinder.cancelCurrentSendPost();
						}
						bindContext.unbindService(connection[0]);
					}

					@Override
					public void onServiceDisconnected(ComponentName componentName) {}
				};
				bindContext.bindService(new Intent(context, PostingService.class), connection[0], BIND_AUTO_CREATE);
			}
		}
	}

	public static class FailResult implements Parcelable {
		public final ErrorItem errorItem;
		public final ApiException.Extra extra;
		public final boolean captchaError;
		public final boolean keepCaptcha;

		public FailResult(ErrorItem errorItem, ApiException.Extra extra, boolean captchaError, boolean keepCaptcha) {
			this.errorItem = errorItem;
			this.extra = extra;
			this.captchaError = captchaError;
			this.keepCaptcha = keepCaptcha;
		}

		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(Parcel dest, int flags) {
			errorItem.writeToParcel(dest, flags);
			dest.writeParcelable(extra, flags);
			dest.writeByte((byte) (captchaError ? 1 : 0));
			dest.writeByte((byte) (keepCaptcha ? 1 : 0));
		}

		public static final Creator<FailResult> CREATOR = new Creator<FailResult>() {
			@Override
			public FailResult createFromParcel(Parcel in) {
				ErrorItem errorItem = ErrorItem.CREATOR.createFromParcel(in);
				ApiException.Extra extra = AndroidUtils.readParcelable(in, FailResult.class.getClassLoader(),
						ApiException.Extra.class);
				boolean captchaError = in.readByte() != 0;
				boolean keepCaptcha = in.readByte() != 0;
				return new FailResult(errorItem, extra, captchaError, keepCaptcha);
			}

			@Override
			public FailResult[] newArray(int size) {
				return new FailResult[size];
			}
		};
	}

	public static class NewPostData {
		public final Key key;
		private final String tag;

		private NewPostData(Key key, PostNumber postNumber, String comment, boolean newThread) {
			this.key = key;
			this.tag = "posting:" + StringUtils.formatHex(Hasher.getInstanceSha256().calculate(key.chanName + "/" +
					key.boardName + "/" + key.threadNumber + "/" + postNumber + "/" + comment + "/" + newThread));
		}
	}

	private static final HashMap<Key, HashSet<PendingUserPost>> PENDING_USER_POST_MAP = new HashMap<>();

	public static Set<PendingUserPost> getPendingUserPosts(String chanName, String boardName,
			String threadNumber) {
		return PENDING_USER_POST_MAP.get(new Key(chanName, boardName, threadNumber));
	}

	public static void consumePendingUserPosts(String chanName, String boardName, String threadNumber,
			Collection<PendingUserPost> consumePendingUserPosts) {
		Key key = new Key(chanName, boardName, threadNumber);
		HashSet<PendingUserPost> pendingUserPosts = PENDING_USER_POST_MAP.remove(key);
		if (pendingUserPosts != null) {
			pendingUserPosts.removeAll(consumePendingUserPosts);
			if (!pendingUserPosts.isEmpty()) {
				PENDING_USER_POST_MAP.put(key, pendingUserPosts);
			}
		}
	}

	private static final HashMap<Key, ArrayList<NewPostData>> NEW_POST_DATA_MAP = new HashMap<>();

	public static boolean consumeNewPostData(Context context, String chanName, String boardName, String threadNumber) {
		ArrayList<NewPostData> newPostDataList = NEW_POST_DATA_MAP
				.remove(new Key(chanName, boardName, threadNumber));
		if (newPostDataList != null) {
			NotificationManager notificationManager = (NotificationManager) context
					.getSystemService(NOTIFICATION_SERVICE);
			for (NewPostData newPostData : newPostDataList) {
				notificationManager.cancel(newPostData.tag, 0);
			}
			return !newPostDataList.isEmpty();
		} else {
			return false;
		}
	}

	private static Pair<Key, NewPostData> newThreadData;

	public static NewPostData consumeNewThreadData(Context context, String chanName, String boardName) {
		Pair<Key, NewPostData> newThreadData = PostingService.newThreadData;
		if (newThreadData != null && newThreadData.first.equals(new Key(chanName, boardName, null))) {
			clearNewThreadData();
			NotificationManager notificationManager = (NotificationManager) context
					.getSystemService(NOTIFICATION_SERVICE);
			notificationManager.cancel(newThreadData.second.tag, 0);
			return newThreadData.second;
		}
		return null;
	}

	public static void clearNewThreadData() {
		newThreadData = null;
	}
}
