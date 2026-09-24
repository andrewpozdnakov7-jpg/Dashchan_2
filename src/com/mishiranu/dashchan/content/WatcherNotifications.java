package com.mishiranu.dashchan.content;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.content.storage.MyPostsStorage;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.Hasher;
import com.mishiranu.dashchan.util.Logger;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class WatcherNotifications {
	private static final String LOG_TAG = "WatcherNotifications";
	private static final String SOURCE_WATCHER_SERVICE = "watcher_service";
	private static final String SOURCE_BACKGROUND = "background";
	private static final String SOURCE_PUSH = "push";
	private static final Executor EXECUTOR = ConcurrentUtils
			.newSingleThreadPool(1000, "WatcherNotifications", null);

	public static void configure(Context context) {
		NotificationManager notificationManager = (NotificationManager)
				context.getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationChannel channel = new NotificationChannel(C.NOTIFICATION_CHANNEL_REPLIES,
				context.getString(R.string.replies), NotificationManager.IMPORTANCE_HIGH);
		channel.enableLights(true);
		channel.enableVibration(true);
		notificationManager.createNotificationChannel(channel);
	}

	public static void notifyReplies(Context context, int color, boolean important, boolean sound, boolean vibration,
			String title, String chanName, String boardName, String threadNumber,
			List<PagesDatabase.InsertResult.Reply> replies) {
		enqueueReplies(context, SOURCE_WATCHER_SERVICE, color, important, sound, vibration, title,
				chanName, boardName, threadNumber, replies);
	}

	public static void notifyBackgroundReplies(Context context, int color, boolean important,
			boolean sound, boolean vibration, String title, String chanName, String boardName,
			String threadNumber, List<PagesDatabase.InsertResult.Reply> replies) {
		enqueueReplies(context, SOURCE_BACKGROUND, color, important, sound, vibration, title,
				chanName, boardName, threadNumber, replies);
	}

	private static void enqueueReplies(Context context, String source, int color, boolean important,
			boolean sound, boolean vibration, String title, String chanName, String boardName,
			String threadNumber, List<PagesDatabase.InsertResult.Reply> replies) {
		EXECUTOR.execute(new Task(context, source, color, important, sound, vibration, title,
				chanName, boardName, threadNumber, replies, Collections.emptyList()));
	}

	public static void notifyPushReply(Context context, String chanName, String boardName, String threadNumber,
			PostNumber postNumber, String comment, long timestamp) {
		configure(context);
		Chan chan = Chan.get(chanName);
		String title = chan.configuration.getTitle() + " / " + boardName + " / " + threadNumber;
		String notificationComment = StringUtils.isEmptyOrWhitespace(comment)
				? context.getString(R.string.reply_push_notification_text) : comment;
		enqueueReplies(context, SOURCE_PUSH, 0, true, true, true, title, chanName, boardName,
				threadNumber, Collections.singletonList(new PagesDatabase.InsertResult.Reply(postNumber,
						notificationComment, timestamp)));
	}

	/** Background delivery worker only: do not commit completion before the notification task finishes. */
	public static boolean notifyPushReplyAndWait(Context context, String chanName, String boardName,
			String threadNumber, PostNumber postNumber, String comment, long timestamp) {
		try {
			configure(context);
			String title = Chan.get(chanName).configuration.getTitle() + " / " + boardName + " / " + threadNumber;
			String text = StringUtils.isEmptyOrWhitespace(comment)
					? context.getString(R.string.reply_push_notification_text) : comment;
			Task notification = new Task(context, SOURCE_PUSH, 0, true, true, true,
					title, chanName, boardName, threadNumber,
					Collections.singletonList(new PagesDatabase.InsertResult.Reply(postNumber, text, timestamp)),
					Collections.emptyList());
			FutureTask<Void> task = new FutureTask<>(() -> {
				// The reader may clear/read this reply while the task waits in the notification queue.
				if (MyPostsStorage.getInstance().isPushPending(chanName, boardName, threadNumber, postNumber)
						&& Preferences.isTrackMyPostsEnabled() && Preferences.isReplyPushEnabled()
						&& Preferences.isTrackedRepliesNotificationsEnabled() && !Preferences.isReplyPushQuietHoursActive()) {
					notification.run();
				}
			}, null);
			EXECUTOR.execute(task);
			task.get();
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (ExecutionException | RuntimeException e) {
			Logger.write(Logger.Type.ERROR, LOG_TAG, "push_delivery_failed", e.getClass().getSimpleName());
			return false;
		}
	}

	public static void cancelReplies(Context context,
			String chanName, String boardName, String threadNumber, Collection<PostNumber> postNumbers) {
		EXECUTOR.execute(new Task(context, "cancel", 0, false, false, false, null,
				chanName, boardName, threadNumber, Collections.emptyList(), postNumbers));
	}

	private static class Task implements Runnable {
		private static final String GROUP_REPLIES = "replies";

		public final Context context;
		public final String source;
		public final int color;
		public final boolean important;
		public final boolean sound;
		public final boolean vibration;
		public final String title;
		public final String chanName;
		public final String boardName;
		public final String threadNumber;
		public final List<PagesDatabase.InsertResult.Reply> replies;
		public final Collection<PostNumber> removePostNumbers;

		private Task(Context context, String source, int color, boolean important,
				boolean sound, boolean vibration, String title,
				String chanName, String boardName, String threadNumber,
				List<PagesDatabase.InsertResult.Reply> replies, Collection<PostNumber> removePostNumbers) {
			this.context = context.getApplicationContext();
			this.source = source;
			this.color = color;
			this.important = important;
			this.sound = sound;
			this.vibration = vibration;
			this.title = title;
			this.chanName = chanName;
			this.boardName = boardName;
			this.threadNumber = threadNumber;
			this.replies = replies;
			this.removePostNumbers = removePostNumbers;
		}

		private static String makeTag(String chanName, String boardName, String threadNumber, PostNumber postNumber) {
			return StringUtils.formatHex(Hasher.getInstanceSha256().calculate(chanName + "/" +
					boardName + "/" + threadNumber + "/" + postNumber));
		}

		private void configureNotification(NotificationCompat.Builder builder, boolean alert) {
			builder.setSmallIcon(R.drawable.ic_notification);
			builder.setColor(color);
			builder.setCategory(Notification.CATEGORY_MESSAGE);
			builder.setPriority(important ? NotificationCompat.PRIORITY_HIGH
					: NotificationCompat.PRIORITY_DEFAULT);
			builder.setAutoCancel(true);
			builder.setGroup(GROUP_REPLIES);
			builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY);
			if (alert) {
				int defaults = 0;
				if (sound) {
					defaults |= Notification.DEFAULT_SOUND;
				}
				if (vibration) {
					defaults |= Notification.DEFAULT_VIBRATE;
				}
				builder.setDefaults(defaults);
				if (!sound && !vibration) {
					builder.setSilent(true);
				}
			}
		}

		private PendingIntent createContentIntent(String tag, PostNumber postNumber) {
			Intent intent = new Intent(context, MainActivity.class).setAction(tag)
					.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
					.putExtra(C.EXTRA_CHAN_NAME, chanName)
					.putExtra(C.EXTRA_BOARD_NAME, boardName)
					.putExtra(C.EXTRA_THREAD_NUMBER, threadNumber)
					.putExtra(C.EXTRA_POST_NUMBER, postNumber.toString());
			return PendingIntent.getActivity(context, 0, intent,
					PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		}

		private static int countActiveReplies(NotificationManager notificationManager) {
			int count = 0;
			StatusBarNotification[] notifications = notificationManager.getActiveNotifications();
			if (notifications != null) {
				for (StatusBarNotification notification : notifications) {
					if (notification.getTag() != null && notification.getId() == C.NOTIFICATION_ID_REPLIES) {
						count++;
					}
				}
			}
			return count;
		}

		private static String buildLongComment(String comment) {
			StringBuilder builder = new StringBuilder();
			boolean nextNewLine = false;
			for (String line : comment.split("\n")) {
				if (!line.isEmpty()) {
					line = line.trim();
					if (!line.isEmpty()) {
						line = line.replaceAll(" {2,}", " ");
						boolean newLine = nextNewLine;
						nextNewLine = !line.contains(">>") || !line.replaceAll(">>\\d+", "").trim().isEmpty();
						if (builder.length() > 0) {
							builder.append(newLine ? '\n' : ' ');
						}
						builder.append(line);
					}
				}
			}
			return builder.toString();
		}

		@Override
		public void run() {
			NotificationManager notificationManager = (NotificationManager)
					context.getSystemService(Context.NOTIFICATION_SERVICE);
			NotificationChannel channel = notificationManager.getNotificationChannel(C.NOTIFICATION_CHANNEL_REPLIES);
			Logger.write(Logger.Type.DEBUG, LOG_TAG, "task_start", "source", source,
					"replies", replies.size(), "important", important, "sound", sound,
					"vibration", vibration,
					"cancellations", removePostNumbers.size(), "notificationsEnabled",
					notificationManager.areNotificationsEnabled(), "channelImportance",
					channel != null ? channel.getImportance() : "missing", "channelSound",
					channel != null && channel.getSound() != null, "channelVibration",
					channel != null && channel.shouldVibrate(), "channelBypassDnd",
					channel != null && channel.canBypassDnd(), "interruptionFilter",
					notificationManager.getCurrentInterruptionFilter(), "activeRepliesBefore",
					countActiveReplies(notificationManager));
			try {
				if (!replies.isEmpty()) {
					notifyReplies(notificationManager);
					Logger.write(Logger.Type.DEBUG, LOG_TAG, "replies_posted", "source", source,
							"count", replies.size(), "activeRepliesAfter",
							countActiveReplies(notificationManager));
				}
				if (!removePostNumbers.isEmpty()) {
					cancelReplies(notificationManager);
					Logger.write(Logger.Type.DEBUG, LOG_TAG, "replies_cancelled", "count",
							removePostNumbers.size());
				}
			} catch (RuntimeException e) {
				Logger.write(Logger.Type.ERROR, LOG_TAG, "task_fail", "error", e.getClass().getName());
				throw e;
			}
		}

		private void notifyReplies(NotificationManager notificationManager) {
			ReplyNotificationDelivery.deliver(replies, SOURCE_PUSH.equals(source),
					new ReplyNotificationDelivery.Sink<PagesDatabase.InsertResult.Reply>() {
				@Override
				public Set<String> activeChildTags() {
					Set<String> tags = new HashSet<>();
					StatusBarNotification[] active = notificationManager.getActiveNotifications();
					if (active != null) {
						for (StatusBarNotification notification : active) {
							if (notification.getId() == C.NOTIFICATION_ID_REPLIES && notification.getTag() != null) {
								tags.add(notification.getTag());
							}
						}
					}
					return tags;
				}

				@Override
				public boolean hasSummary() {
					StatusBarNotification[] active = notificationManager.getActiveNotifications();
					if (active != null) {
						for (StatusBarNotification notification : active) {
							if (notification.getId() == C.NOTIFICATION_ID_REPLIES && notification.getTag() == null) {
								return true;
							}
						}
					}
					return false;
				}

				@Override
				public String tag(PagesDatabase.InsertResult.Reply reply) {
					return makeTag(chanName, boardName, threadNumber, reply.postNumber);
				}

				@Override
				public void postChild(PagesDatabase.InsertResult.Reply reply) {
					notifyChild(notificationManager, reply);
				}

				@Override
				public void postSummary(List<PagesDatabase.InsertResult.Reply> group, boolean alert) {
					notifySummary(notificationManager, group, alert);
				}
			});
		}

		private void notifyChild(NotificationManager notificationManager, PagesDatabase.InsertResult.Reply reply) {
			String title = context.getString(R.string.reply_in_thread__format, this.title);
			NotificationCompat.Builder builder = new NotificationCompat
					.Builder(context, C.NOTIFICATION_CHANNEL_REPLIES);
			String comment = StringUtils.clearHtml(reply.comment);
			String text = comment.replace('\n', ' ').replaceAll(" {2,}", " ");
			builder.setContentTitle(title);
			builder.setContentText(text);
			if (important) builder.setTicker((title + "\n" + text).trim());
			builder.setStyle(new NotificationCompat.BigTextStyle().bigText(buildLongComment(comment)));
			builder.setWhen(reply.timestamp);
			configureNotification(builder, false);
			String tag = makeTag(chanName, boardName, threadNumber, reply.postNumber);
			builder.setContentIntent(createContentIntent(tag, reply.postNumber));
			notificationManager.notify(tag, C.NOTIFICATION_ID_REPLIES, builder.build());
		}

		private void notifySummary(NotificationManager notificationManager,
				List<PagesDatabase.InsertResult.Reply> group, boolean alert) {
			String title = context.getString(R.string.reply_in_thread__format, this.title);
			NotificationCompat.InboxStyle summaryStyle = new NotificationCompat.InboxStyle();
			PendingIntent summaryIntent = null;
			long newestTimestamp = 0L;
			for (PagesDatabase.InsertResult.Reply reply : group) {
				String comment = StringUtils.clearHtml(reply.comment);
				String text = comment.replace('\n', ' ').replaceAll(" {2,}", " ");
				if (summaryIntent == null) {
					summaryIntent = createContentIntent(makeTag(chanName, boardName, threadNumber, reply.postNumber),
							reply.postNumber);
				}
				newestTimestamp = Math.max(newestTimestamp, reply.timestamp);
				summaryStyle.addLine(text);
			}
			int activeReplies = Math.max(group.size(), countActiveReplies(notificationManager));
			String summaryTitle = context.getResources().getQuantityString(
					R.plurals.new_replies_count__format, activeReplies, activeReplies);
			NotificationCompat.Builder builder = new NotificationCompat
					.Builder(context, C.NOTIFICATION_CHANNEL_REPLIES);
			configureNotification(builder, alert);
			if (!alert) builder.setSilent(true);
			builder.setContentTitle(summaryTitle);
			builder.setContentText(title);
			builder.setStyle(summaryStyle.setBigContentTitle(summaryTitle));
			builder.setNumber(activeReplies);
			if (newestTimestamp > 0L) {
				builder.setWhen(newestTimestamp);
			}
			if (summaryIntent != null) {
				builder.setContentIntent(summaryIntent);
			}
			builder.setGroupSummary(true);
			notificationManager.notify(C.NOTIFICATION_ID_REPLIES, builder.build());
		}

		private void cancelReplies(NotificationManager notificationManager) {
			Set<String> tags = null;
			StatusBarNotification[] notifications = notificationManager.getActiveNotifications();
			if (notifications != null && notifications.length > 0) {
				tags = new HashSet<>();
				for (StatusBarNotification notification : notifications) {
					String tag = notification.getTag();
					if (tag != null && notification.getId() == C.NOTIFICATION_ID_REPLIES) {
						tags.add(tag);
					}
				}
			}
			for (PostNumber postNumber : removePostNumbers) {
				String tag = makeTag(chanName, boardName, threadNumber, postNumber);
				notificationManager.cancel(tag, C.NOTIFICATION_ID_REPLIES);
				if (tags != null) {
					tags.remove(tag);
				}
			}
			if (tags != null && tags.isEmpty()) {
				notificationManager.cancel(C.NOTIFICATION_ID_REPLIES);
			}
		}
	}
}
