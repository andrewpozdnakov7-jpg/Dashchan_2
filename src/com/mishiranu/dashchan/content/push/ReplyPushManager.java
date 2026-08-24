package com.mishiranu.dashchan.content.push;

import android.content.Context;
import chan.util.StringUtils;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.WatcherNotifications;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.content.storage.MyPostsStorage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ReplyPushManager {
	private static final String SUPPORTED_CHAN_NAME = "dvach";
	private static final int MAX_HANDLED_EVENTS = 256;
	private static final Object EVENTS_LOCK = new Object();

	private ReplyPushManager() {}

	public static boolean isSupported() {
		return ReplyPushBridge.isSupported();
	}

	public static boolean isConfigured() {
		return ReplyPushBridge.isConfigured();
	}

	public static void enable(Context context) {
		if (!Preferences.isTrackMyPostsEnabled() || !Preferences.isReplyPushEnabled() || !isSupported()) {
			return;
		}
		String installationId = Preferences.getReplyPushInstallationId();
		if (StringUtils.isEmpty(installationId)) {
			installationId = UUID.randomUUID().toString();
			Preferences.setReplyPushInstallationId(installationId);
		}
		ReplyPushBridge.setEnabled(context.getApplicationContext(), true, installationId);
		for (MyPostsStorage.TrackedPost post : MyPostsStorage.getInstance().getPosts()) {
			registerWatch(context, installationId, post.chanName, post.boardName,
					post.threadNumber, post.postNumber);
		}
	}

	public static void disable(Context context) {
		String installationId = Preferences.getReplyPushInstallationId();
		if (isSupported() && !StringUtils.isEmpty(installationId)) {
			ReplyPushBridge.setEnabled(context.getApplicationContext(), false, installationId);
		}
	}

	public static void restore(Context context) {
		if (Preferences.isReplyPushEnabled() && !Preferences.isTrackMyPostsEnabled()) {
			disable(context);
			Preferences.setReplyPushEnabled(false);
		} else if (Preferences.isReplyPushEnabled()) {
			enable(context);
		}
	}

	public static void onPostTracked(Context context, String chanName, String boardName,
			String threadNumber, PostNumber postNumber) {
		if (!Preferences.isTrackMyPostsEnabled() || !Preferences.isReplyPushEnabled()) {
			return;
		}
		String installationId = Preferences.getReplyPushInstallationId();
		if (!StringUtils.isEmpty(installationId)) {
			registerWatch(context, installationId, chanName, boardName, threadNumber, postNumber);
		}
	}

	private static void registerWatch(Context context, String installationId, String chanName,
			String boardName, String threadNumber, PostNumber postNumber) {
		if (SUPPORTED_CHAN_NAME.equals(chanName) && !StringUtils.isEmpty(boardName)
				&& !StringUtils.isEmpty(threadNumber) && postNumber != null) {
			ReplyPushBridge.registerWatch(context.getApplicationContext(), installationId,
					chanName, boardName, threadNumber, postNumber.toString());
		}
	}

	public static boolean handleData(Context context, Map<String, String> data) {
		if (!Preferences.isTrackMyPostsEnabled() || !Preferences.isReplyPushEnabled()) {
			return false;
		}
		ReplyPushMessage message = ReplyPushMessage.parse(data);
		if (message == null || isHandled(message.eventId)) {
			return false;
		}
		boolean added = MyPostsStorage.getInstance().addReply(message.chanName, message.boardName,
				message.threadNumber, message.trackedPostNumber, message.replyPostNumber,
				message.comment, message.timestamp);
		markHandled(message.eventId);
		if (added && Preferences.isTrackedRepliesNotificationsEnabled()) {
			WatcherNotifications.notifyPushReply(context, message.chanName, message.boardName,
					message.threadNumber, message.replyPostNumber, message.comment, message.timestamp);
		}
		return added;
	}

	public static boolean handleMockReply(Context context, String boardName, String threadNumber,
			PostNumber trackedPostNumber, PostNumber replyPostNumber, String comment) {
		HashMap<String, String> data = new HashMap<>();
		data.put("type", "reply");
		data.put("schema_version", "1");
		data.put("event_id", "mock:" + UUID.randomUUID());
		data.put("chan_name", SUPPORTED_CHAN_NAME);
		data.put("board", boardName);
		data.put("thread_id", threadNumber);
		data.put("watched_post_id", trackedPostNumber.toString());
		data.put("reply_post_id", replyPostNumber.toString());
		if (!StringUtils.isEmpty(comment)) {
			data.put("comment", comment);
		}
		return handleData(context, data);
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
}
