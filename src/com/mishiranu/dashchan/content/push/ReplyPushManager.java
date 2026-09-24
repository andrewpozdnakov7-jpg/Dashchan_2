package com.mishiranu.dashchan.content.push;

import android.content.Context;
import chan.util.StringUtils;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.WatcherNotifications;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.content.storage.MyPostsStorage;
import com.mishiranu.dashchan.util.Logger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReplyPushManager {
	private static final String LOG_TAG = "ReplyPush";
	private static final int MAX_HANDLED_EVENTS = 256;
	private static final int MAX_NOTIFIED_REPLIES = 256;
	private static final Object EVENTS_LOCK = new Object();
	private static final Object DELIVERY_LOCK = new Object();

	public enum DeliveryResult { HANDLED, IGNORED, RETRY }

	private ReplyPushManager() {}

	public static boolean isSupported() {
		return ReplyPushBridge.isSupported();
	}

	public static boolean isConfigured() {
		return ReplyPushBridge.isConfigured();
	}

	public static boolean hasConsent(Context context) {
		return ReplyPushBridge.hasConsent(context.getApplicationContext());
	}

	public static String getInstallationId(Context context) {
		return ReplyPushBridge.getInstallationId(context.getApplicationContext());
	}

	public static boolean isIdentityResetPending(Context context) {
		return ReplyPushBridge.isIdentityResetPending(context.getApplicationContext());
	}

	public static boolean didIdentityResetFail(Context context) {
		return ReplyPushBridge.didIdentityResetFail(context.getApplicationContext());
	}

	public static long getIdentityResetCooldownRemaining(Context context) {
		return ReplyPushBridge.getIdentityResetCooldownRemaining(context.getApplicationContext());
	}

	public static boolean resetIdentity(Context context) {
		if (!Preferences.isTrackMyPostsEnabled() || !Preferences.isReplyPushEnabled()
				|| !isSupported() || !isConfigured()) {
			return false;
		}
		String installationId = getInstallationId(context);
		if (!ReplyPushBridge.resetIdentity(context.getApplicationContext(), installationId)) {
			return false;
		}
		MyPostsStorage.getInstance().deactivateAllTracking();
		return true;
	}

	public static void enable(Context context) {
		if (!Preferences.isTrackMyPostsEnabled() || !Preferences.isReplyPushEnabled() || !isSupported()
				|| isIdentityResetPending(context)) {
			return;
		}
		String installationId = getInstallationId(context);
		if (StringUtils.isEmpty(installationId)) {
			installationId = ReplyPushContract.newInstallationId();
			ReplyPushBridge.setInstallationId(context.getApplicationContext(), installationId);
		}
		ReplyPushBridge.setEnabled(context.getApplicationContext(), true, installationId);
	}

	public static void disable(Context context) {
		String installationId = getInstallationId(context);
		if (isSupported() && !StringUtils.isEmpty(installationId)) {
			ReplyPushBridge.setEnabled(context.getApplicationContext(), false, installationId);
		}
	}

	public static void restore(Context context) {
		if (Preferences.isReplyPushEnabled() && !hasConsent(context)) {
			Preferences.setReplyPushEnabled(false);
			ReplyPushBridge.setInstallationId(context.getApplicationContext(), null);
			return;
		}
		if (Preferences.isReplyPushEnabled()
				&& (!Preferences.isTrackMyPostsEnabled() || !isSupported())) {
			Preferences.setReplyPushEnabled(false);
			if (isSupported()) {
				disable(context);
			} else {
				ReplyPushBridge.setInstallationId(context.getApplicationContext(), null);
			}
		} else if (Preferences.isReplyPushEnabled()) {
			enable(context);
		}
	}

	public static void onPostTracked(Context context, String chanName, String boardName,
			String threadNumber, PostNumber postNumber) {
		if (!Preferences.isTrackMyPostsEnabled() || !Preferences.isReplyPushEnabled()) {
			return;
		}
		if (isIdentityResetPending(context)) {
			MyPostsStorage.getInstance().deactivateTracking(chanName, boardName, threadNumber, postNumber);
			return;
		}
		String installationId = getInstallationId(context);
		if (!StringUtils.isEmpty(installationId)) {
			registerWatch(context, installationId, chanName, boardName, threadNumber, postNumber);
		}
	}

	private static void registerWatch(Context context, String installationId, String chanName,
			String boardName, String threadNumber, PostNumber postNumber) {
		if (ReplyPushContract.SUPPORTED_CHAN_NAME.equals(chanName)
				&& ReplyPushContract.SUPPORTED_BOARD_NAME.equals(boardName)
				&& !StringUtils.isEmpty(threadNumber) && postNumber != null) {
			ReplyPushBridge.registerWatch(context.getApplicationContext(), installationId,
					chanName, boardName, threadNumber, postNumber.toString());
		}
	}

	public static boolean handleData(Context context, Map<String, String> data) {
		return processData(context, data) == DeliveryResult.HANDLED;
	}

	public static DeliveryResult processData(Context context, Map<String, String> data) {
		synchronized (DELIVERY_LOCK) {
			return processDataLocked(context, data);
		}
	}

	private static DeliveryResult processDataLocked(Context context, Map<String, String> data) {
		ReplyPushMessage message = ReplyPushMessage.parse(data);
		if (message == null) {
			Logger.write(Logger.Type.DEBUG, LOG_TAG, "message_ignored", "reason", "invalid");
			return DeliveryResult.IGNORED;
		}
		boolean trackingEnabled = Preferences.isTrackMyPostsEnabled();
		boolean pushEnabled = Preferences.isReplyPushEnabled();
		Logger.write(Logger.Type.DEBUG, LOG_TAG, "message_received", "fields", data.size(),
				"trackingEnabled", trackingEnabled, "pushEnabled", pushEnabled);
		if (!trackingEnabled || !pushEnabled) {
			Logger.write(Logger.Type.DEBUG, LOG_TAG, "message_ignored", "reason", "disabled");
			MyPostsStorage storage = MyPostsStorage.getInstance();
			storage.completePushReply(message.chanName, message.boardName, message.threadNumber,
					message.trackedPostNumber, message.replyPostNumber);
			return storage.awaitSaved() ? DeliveryResult.IGNORED : DeliveryResult.RETRY;
		}
		if (isHandled(message.eventId)) {
			Logger.write(Logger.Type.DEBUG, LOG_TAG, "message_ignored", "reason", "duplicate");
			return DeliveryResult.IGNORED;
		}
		MyPostsStorage storage = MyPostsStorage.getInstance();
		MyPostsStorage.AddReplyResult result = storage.addReply(message.chanName, message.boardName,
				message.threadNumber, message.trackedPostNumber, message.replyPostNumber,
				message.comment, message.timestamp, true);
		if (result == MyPostsStorage.AddReplyResult.NOT_TRACKED) {
			// Keep the event retryable when the local and server watch lists are temporarily out of sync.
			Logger.write(Logger.Type.DEBUG, LOG_TAG, "message_ignored", "reason", "not_tracked");
			return DeliveryResult.IGNORED;
		}
		boolean added = result == MyPostsStorage.AddReplyResult.ADDED;
		// Also flush ALREADY_EXISTS: a previous delivery may only exist in memory after an I/O failure.
		// This is an application-level handled marker, not an acknowledgement/retry contract with FCM.
		boolean[] recovered = {false};
		boolean success = ReplyDelivery.finish(new ReplyDelivery.Steps() {
			@Override
			public boolean persist() {
				boolean saved = storage.awaitSaved();
				if (!saved) Logger.write(Logger.Type.ERROR, LOG_TAG, "message_save_failed", "handled", false);
				return saved;
			}

			@Override
			public boolean deliverPending() {
				MyPostsStorage.Reply pending = storage.getPendingPushReply(message.chanName, message.boardName,
						message.threadNumber, message.trackedPostNumber, message.replyPostNumber);
				recovered[0] = pending != null;
				if (pending != null) markPushNotified(message.chanName, message.boardName,
						message.threadNumber, message.replyPostNumber);
				boolean enabled = Preferences.isTrackedRepliesNotificationsEnabled();
				boolean quiet = Preferences.isReplyPushQuietHoursActive();
				Logger.write(Logger.Type.DEBUG, LOG_TAG, "message_stored", "added", added,
						"pending", pending != null, "notificationsEnabled", enabled, "quietHours", quiet);
				return pending == null || !enabled || quiet || WatcherNotifications.notifyPushReplyAndWait(context,
						message.chanName, message.boardName, message.threadNumber, message.replyPostNumber,
						pending.comment, pending.time);
			}

			@Override
			public void complete() {
				storage.completePushReply(message.chanName, message.boardName, message.threadNumber,
						message.trackedPostNumber, message.replyPostNumber);
			}

			@Override
			public void markHandled() { ReplyPushManager.markHandled(message.eventId); }
		});
		return !success ? DeliveryResult.RETRY
				: added || recovered[0] ? DeliveryResult.HANDLED : DeliveryResult.IGNORED;
	}

	public static List<PagesDatabase.InsertResult.Reply> filterPushNotifiedReplies(String chanName,
			String boardName, String threadNumber, List<PagesDatabase.InsertResult.Reply> replies) {
		if (replies.isEmpty()) {
			return replies;
		}
		synchronized (EVENTS_LOCK) {
			Set<String> stored = Preferences.getReplyPushNotifiedReplies();
			LinkedHashSet<String> notified = new LinkedHashSet<>(stored);
			ArrayList<PagesDatabase.InsertResult.Reply> filtered = null;
			for (int i = 0; i < replies.size(); i++) {
				PagesDatabase.InsertResult.Reply reply = replies.get(i);
				if (notified.remove(makeReplyKey(chanName, boardName, threadNumber, reply.postNumber))
						|| MyPostsStorage.getInstance().isPushPending(chanName, boardName, threadNumber, reply.postNumber)) {
					if (filtered == null) {
						filtered = new ArrayList<>(replies.subList(0, i));
					}
				} else if (filtered != null) {
					filtered.add(reply);
				}
			}
			if (notified.size() != stored.size()) {
				Preferences.setReplyPushNotifiedReplies(notified);
			}
			return filtered != null ? filtered : replies;
		}
	}

	public static boolean handleMockReply(Context context, String boardName, String threadNumber,
			PostNumber trackedPostNumber, PostNumber replyPostNumber, boolean repeat) {
		HashMap<String, String> data = new HashMap<>();
		data.put("type", "reply");
		data.put("schema_version", "1");
		data.put("event_id", ReplyPushContract.sha256(ReplyPushContract.newInstallationId()));
		data.put("chan_name", ReplyPushContract.SUPPORTED_CHAN_NAME);
		data.put("board", boardName);
		data.put("thread_id", threadNumber);
		data.put("watched_post_id", trackedPostNumber.toString());
		data.put("reply_post_id", replyPostNumber.toString());
		boolean added = handleData(context, data);
		if (repeat) {
			handleData(context, data);
		}
		return added;
	}

	private static boolean isHandled(String eventId) {
		synchronized (EVENTS_LOCK) {
			return Preferences.getReplyPushHandledEvents().contains(eventId);
		}
	}

	private static void markHandled(String eventId) {
		synchronized (EVENTS_LOCK) {
			Set<String> stored = Preferences.getReplyPushHandledEvents();
			LinkedHashSet<String> events = new LinkedHashSet<>(stored);
			events.add(eventId);
			while (events.size() > MAX_HANDLED_EVENTS) {
				Iterator<String> iterator = events.iterator();
				iterator.next();
				iterator.remove();
			}
			Preferences.setReplyPushHandledEvents(events);
		}
	}

	private static void markPushNotified(String chanName, String boardName, String threadNumber,
			PostNumber replyPostNumber) {
		synchronized (EVENTS_LOCK) {
			LinkedHashSet<String> replies = new LinkedHashSet<>(Preferences.getReplyPushNotifiedReplies());
			replies.add(makeReplyKey(chanName, boardName, threadNumber, replyPostNumber));
			while (replies.size() > MAX_NOTIFIED_REPLIES) {
				Iterator<String> iterator = replies.iterator();
				iterator.next();
				iterator.remove();
			}
			Preferences.setReplyPushNotifiedReplies(replies);
		}
	}

	private static String makeReplyKey(String chanName, String boardName, String threadNumber,
			PostNumber replyPostNumber) {
		return chanName + '\n' + StringUtils.emptyIfNull(boardName) + '\n' + threadNumber + '\n'
				+ replyPostNumber;
	}
}
