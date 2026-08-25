package com.mishiranu.dashchan.content.push;

import com.mishiranu.dashchan.content.model.PostNumber;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ReplyPushMessage {
	private static final Pattern EVENT_ID_PATTERN = Pattern.compile("[0-9a-f]{64}");
	private static final Set<String> EXPECTED_KEYS = new HashSet<>(Arrays.asList("type", "schema_version",
			"event_id", "chan_name", "board", "thread_id", "watched_post_id", "reply_post_id"));

	public final String eventId;
	public final String chanName;
	public final String boardName;
	public final String threadNumber;
	public final PostNumber trackedPostNumber;
	public final PostNumber replyPostNumber;
	public final String comment;
	public final long timestamp;

	private ReplyPushMessage(String eventId, String chanName, String boardName, String threadNumber,
			PostNumber trackedPostNumber, PostNumber replyPostNumber, String comment, long timestamp) {
		this.eventId = eventId;
		this.chanName = chanName;
		this.boardName = boardName;
		this.threadNumber = threadNumber;
		this.trackedPostNumber = trackedPostNumber;
		this.replyPostNumber = replyPostNumber;
		this.comment = comment;
		this.timestamp = timestamp;
	}

	public static ReplyPushMessage parse(Map<String, String> data) {
		if (data == null || !EXPECTED_KEYS.equals(data.keySet()) || !"reply".equals(data.get("type"))
				|| !"1".equals(data.get("schema_version"))) {
			return null;
		}
		String eventId = data.get("event_id");
		String chanName = data.get("chan_name");
		String boardName = data.get("board");
		String threadNumber = data.get("thread_id");
		String trackedPost = data.get("watched_post_id");
		String replyPost = data.get("reply_post_id");
		if (eventId == null || !EVENT_ID_PATTERN.matcher(eventId).matches()
				|| !ReplyPushContract.SUPPORTED_CHAN_NAME.equals(chanName)
				|| !ReplyPushContract.SUPPORTED_BOARD_NAME.equals(boardName)
				|| !ReplyPushContract.isCanonicalPositiveNumber(threadNumber)
				|| !ReplyPushContract.isCanonicalPositiveNumber(trackedPost)
				|| !ReplyPushContract.isCanonicalPositiveNumber(replyPost)) {
			return null;
		}
		PostNumber trackedPostNumber = PostNumber.parseNullable(trackedPost);
		PostNumber replyPostNumber = PostNumber.parseNullable(replyPost);
		if (trackedPostNumber == null || replyPostNumber == null) {
			return null;
		}
		return new ReplyPushMessage(eventId, chanName, boardName, threadNumber,
				trackedPostNumber, replyPostNumber, null, System.currentTimeMillis());
	}
}
