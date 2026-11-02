package com.mishiranu.dashchan.content.push;

import chan.content.Chan;
import chan.content.ChanPerformer;
import chan.content.ExtensionException;
import chan.content.InvalidResponseException;
import chan.content.model.SinglePost;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpRequest;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.text.HtmlParser;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.Logger;
import java.util.Set;

/** Worker-owned read from the board's existing API, never the push relay. No View or UI callbacks. */
public final class ReplyPushTextReader implements AutoCloseable {
	private volatile HttpHolder holder;
	private volatile boolean cancelled;
	private boolean retryable = true;
	public boolean isRetryable() { return retryable; }
	private static final class Data extends ChanPerformer.ReadSinglePostData implements HttpRequest.TimeoutsPreset {
		Data(ReplyPushMessage message, HttpHolder holder) { super(message.boardName, message.replyPostNumber.toString(), holder); }
		@Override public int getConnectTimeout() { return 8000; }
		@Override public int getReadTimeout() { return 8000; }
	}
	public ReplyPushMessage read(ReplyPushMessage message) {
		Chan chan = Chan.get(message.chanName);
		HttpHolder current = new HttpHolder(chan); holder = current;
		Runnable deadline = current::interrupt;
		ConcurrentUtils.HANDLER.postDelayed(deadline, 15000);
		try (HttpHolder.Use ignored = current.use()) {
			current.setMayResolveFirewallBlock(false);
			if (cancelled) { current.interrupt(); return null; }
			ChanPerformer.ReadSinglePostResult result = chan.performer.safe().onReadSinglePost(new Data(message, current));
			if (cancelled || current.isInterrupted()) return null;
			ReplyPushMessage resolved = accept(message, result != null ? result.post : null);
			if (resolved == null) { retryable = false; note("identity_or_reference_mismatch"); }
			return resolved;
		} catch (HttpException e) {
			retryable = e.getResponseCode() != 404 && e.getResponseCode() != 410;
			note("http_" + e.getResponseCode()); return null;
		} catch (ExtensionException | InvalidResponseException | RuntimeException e) {
			note("read_failed_" + e.getClass().getSimpleName()); return null;
		} finally {
			ConcurrentUtils.HANDLER.removeCallbacks(deadline); holder = null;
			try { chan.configuration.commit(); } catch (RuntimeException e) { note("configuration_commit_failed"); }
		}
	}
	static ReplyPushMessage accept(ReplyPushMessage message, SinglePost post) {
		if (post == null || post.post.deleted || !message.threadNumber.equals(post.threadNumber)
				|| !message.replyPostNumber.equals(post.post.number)) return null;
		Set<PostNumber> references = PostItem.collectReferences(null, post.post.comment);
		if (references == null || !references.contains(message.trackedPostNumber)) return null;
		return message.withContent(HtmlParser.clear(post.post.comment), post.post.timestamp);
	}
	private static void note(String reason) {
		Logger.write(Logger.Type.DEBUG, "ReplyPush", "reply_text", reason); // Code/count only; no post text or URL.
	}
	@Override public void close() {
		cancelled = true; HttpHolder current = holder; if (current != null) current.interrupt();
	}
}
