package com.mishiranu.dashchan.content.push;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class ReplyPushMessageTest {
	private static Map<String, String> createValidData() {
		HashMap<String, String> data = new HashMap<>();
		data.put("type", "reply");
		data.put("schema_version", "1");
		data.put("event_id", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
		data.put("chan_name", "dvach");
		data.put("board", "mobi");
		data.put("thread_id", "123");
		data.put("watched_post_id", "456");
		data.put("reply_post_id", "789");
		return data;
	}

	@Test
	public void parsesValidReply() {
		ReplyPushMessage message = ReplyPushMessage.parse(createValidData());
		assertNotNull(message);
		assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
				message.eventId);
		assertEquals("dvach", message.chanName);
		assertEquals("mobi", message.boardName);
		assertEquals("123", message.threadNumber);
		assertEquals("456", message.trackedPostNumber.toString());
		assertEquals("789", message.replyPostNumber.toString());
		assertNull(message.comment);
	}

	@Test
	public void rejectsUnsupportedChan() {
		Map<String, String> data = createValidData();
		data.put("chan_name", "endchan");
		assertNull(ReplyPushMessage.parse(data));
	}

	@Test
	public void rejectsUnsupportedBoard() {
		Map<String, String> data = createValidData();
		data.put("board", "b");
		assertNull(ReplyPushMessage.parse(data));
	}

	@Test
	public void rejectsMalformedLocation() {
		Map<String, String> data = createValidData();
		data.put("board", "../mobi");
		assertNull(ReplyPushMessage.parse(data));
		data = createValidData();
		data.put("thread_id", "0");
		assertNull(ReplyPushMessage.parse(data));
	}

	@Test
	public void rejectsUnknownField() {
		Map<String, String> data = createValidData();
		data.put("comment", "not part of schema v1");
		assertNull(ReplyPushMessage.parse(data));
	}

	@Test
	public void rejectsMalformedEventId() {
		Map<String, String> data = createValidData();
		data.put("event_id", "reply:mobi:123:456:789");
		assertNull(ReplyPushMessage.parse(data));
	}

	@Test
	public void rejectsNonCanonicalOrUnsafeNumbers() {
		Map<String, String> data = createValidData();
		data.put("thread_id", "00123");
		assertNull(ReplyPushMessage.parse(data));
		data = createValidData();
		data.put("reply_post_id", "9007199254740992");
		assertNull(ReplyPushMessage.parse(data));
	}
}
