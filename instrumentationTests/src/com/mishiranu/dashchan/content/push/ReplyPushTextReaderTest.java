package com.mishiranu.dashchan.content.push;

import static org.junit.Assert.*;
import chan.content.model.SinglePost;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** Local single-post results; no network, worker scheduling, notification or user-store mutation. ADDED / NOT RUN. */
public class ReplyPushTextReaderTest {
	private ReplyPushMessage message() {
		Map<String, String> data = new HashMap<>(); data.put("type", "reply"); data.put("schema_version", "1");
		data.put("event_id", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
		data.put("chan_name", "dvach"); data.put("board", "mobi"); data.put("thread_id", "123");
		data.put("watched_post_id", "456"); data.put("reply_post_id", "789"); return ReplyPushMessage.parse(data);
	}
	private SinglePost post(String thread, int number, int reference, boolean deleted) {
		Post.Builder b = new Post.Builder(); b.number = new PostNumber(number, 0); b.timestamp = 45678;
		b.comment = "<a href=\"#" + reference + "\">&gt;&gt;" + reference + "</a><br><b>hello</b> &amp; world";
		return new SinglePost(b.build(deleted), thread, new PostNumber(123, 0));
	}
	@Test public void matchingNativePostSuppliesPlainTextWithoutChangingRoutingIdentity() {
		ReplyPushMessage original = message(), result = ReplyPushTextReader.accept(original, post("123", 789, 456, false));
		assertNotNull(result); assertTrue(original.sameEvent(result)); assertTrue(result.comment.contains("hello"));
		assertTrue(result.comment.contains("& world")); assertFalse(result.comment.contains("<b>")); assertEquals(45678, result.timestamp);
	}
	@Test public void wrongThreadPostReferenceAndDeletedPostsNeverSupplyNotificationText() {
		assertNull(ReplyPushTextReader.accept(message(), post("124", 789, 456, false)));
		assertNull(ReplyPushTextReader.accept(message(), post("123", 790, 456, false)));
		assertNull(ReplyPushTextReader.accept(message(), post("123", 789, 455, false)));
		assertNull(ReplyPushTextReader.accept(message(), post("123", 789, 456, true)));
		assertNull(ReplyPushTextReader.accept(message(), null));
	}
}
