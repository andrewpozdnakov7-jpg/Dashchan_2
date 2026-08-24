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
		data.put("event_id", "reply:mobi:123:456:789");
		data.put("chan_name", "dvach");
		data.put("board", "mobi");
		data.put("thread_id", "123");
		data.put("watched_post_id", "456");
		data.put("reply_post_id", "789");
		data.put("timestamp", "1234567890");
		return data;
	}

	@Test
	public void parsesValidReply() {
		ReplyPushMessage message = ReplyPushMessage.parse(createValidData());
		assertNotNull(message);
		assertEquals("reply:mobi:123:456:789", message.eventId);
		assertEquals("dvach", message.chanName);
		assertEquals("mobi", message.boardName);
		assertEquals("123", message.threadNumber);
		assertEquals("456", message.trackedPostNumber.toString());
		assertEquals("789", message.replyPostNumber.toString());
		assertEquals(1234567890L, message.timestamp);
	}

	@Test
	public void rejectsUnsupportedChan() {
		Map<String, String> data = createValidData();
		data.put("chan_name", "endchan");
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
	public void rejectsMalformedTimestamp() {
		Map<String, String> data = createValidData();
		data.put("timestamp", "tomorrow");
		assertNull(ReplyPushMessage.parse(data));
	}

	@Test
	public void rejectsOversizedComment() {
		Map<String, String> data = createValidData();
		data.put("comment", new String(new char[4001]).replace('\0', 'x'));
		assertNull(ReplyPushMessage.parse(data));
	}
}
