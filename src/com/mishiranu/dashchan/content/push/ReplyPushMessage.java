package com.mishiranu.dashchan.content.push;

import chan.util.StringUtils;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.util.Map;
import java.util.regex.Pattern;

public final class ReplyPushMessage {
	private static final Pattern EVENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
	private static final Pattern BOARD_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	private static final int MAX_COMMENT_LENGTH = 4000;

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
		if (data == null || data.size() > 16 || !"reply".equals(data.get("type"))
				|| !"1".equals(data.get("schema_version"))) {
			return null;
		}
		String eventId = data.get("event_id");
		String chanName = data.get("chan_name");
		String boardName = data.get("board");
		String threadNumber = data.get("thread_id");
		PostNumber trackedPostNumber = PostNumber.parseNullable(data.get("watched_post_id"));
		PostNumber replyPostNumber = PostNumber.parseNullable(data.get("reply_post_id"));
		if (StringUtils.isEmpty(eventId) || !EVENT_ID_PATTERN.matcher(eventId).matches()
				|| !"dvach".equals(chanName) || StringUtils.isEmpty(boardName)
				|| !BOARD_PATTERN.matcher(boardName).matches() || !isPositiveNumber(threadNumber)
				|| trackedPostNumber == null || replyPostNumber == null) {
			return null;
		}
		String comment = StringUtils.nullIfEmpty(data.get("comment"));
		if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
			return null;
		}
		Long timestamp = parseTimestamp(data.get("timestamp"));
		if (timestamp == null) {
			return null;
		}
		return new ReplyPushMessage(eventId, chanName, boardName, threadNumber,
				trackedPostNumber, replyPostNumber, comment, timestamp);
	}

	private static boolean isPositiveNumber(String value) {
		if (StringUtils.isEmpty(value) || value.length() > 32) {
			return false;
		}
		boolean nonZero = false;
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) < '0' || value.charAt(i) > '9') {
				return false;
			}
			nonZero |= value.charAt(i) != '0';
		}
		return nonZero;
	}

	private static Long parseTimestamp(String value) {
		if (StringUtils.isEmpty(value)) {
			return System.currentTimeMillis();
		}
		try {
			long timestamp = Long.parseLong(value);
			return timestamp > 0L ? timestamp : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
