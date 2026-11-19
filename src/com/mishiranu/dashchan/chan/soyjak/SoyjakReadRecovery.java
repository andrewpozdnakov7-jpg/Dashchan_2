package com.mishiranu.dashchan.chan.soyjak;

import android.net.Uri;
import androidx.lifecycle.ViewModel;
import chan.content.ChanPerformer;
import chan.content.model.BoardCategory;
import chan.content.model.Post;
import chan.content.model.Posts;
import com.mishiranu.dashchan.ui.navigator.Page;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONException;

/** Hands the foreground browser document back to its requesting page. Main thread only. */
public final class SoyjakReadRecovery extends ViewModel {
	public record Request(String token, String owner, Page.Content content, String board, String thread,
			int pageNumber, boolean append, Uri uri) {
		public Result parse(String document, Uri documentUri, SoyjakChanLocator locator) throws JSONException {
			SoyjakReaderPage reader = SoyjakReaderPage.from(documentUri);
			if (reader == null || !SoyjakReaderPage.sameReaderTarget(uri.toString(), documentUri.toString())) {
				throw new JSONException("Document target changed");
			}
			return switch (content) {
				case BOARDS -> new Result(this, new ChanPerformer.ReadBoardsResult(
						new BoardCategory("soyjak.st", reader.readBoards(document, documentUri, locator))), null, null);
				case THREADS -> new Result(this, null, new ChanPerformer.ReadThreadsResult(
						reader.read(document, documentUri, locator)), null);
				case POSTS -> {
					ArrayList<Post> posts = new ArrayList<>();
					for (Posts group : reader.read(document, documentUri, locator)) {
						Collections.addAll(posts, group.getPosts());
					}
					yield new Result(this, null, null, new ChanPerformer.ReadPostsResult(posts));
				}
				default -> throw new JSONException("Unexpected page");
			};
		}
	}

	public record Result(Request request, ChanPerformer.ReadBoardsResult boards,
			ChanPerformer.ReadThreadsResult threads, ChanPerformer.ReadPostsResult posts) {}

	private Request pending;
	private Result ready;

	public Request start(String owner, Page page, int pageNumber, boolean append, Uri uri) {
		if (pending != null) return null;
		ready = null;
		pending = new Request(UUID.randomUUID().toString(), owner, page.content, page.boardName,
				page.threadNumber, pageNumber, append, uri);
		return pending;
	}

	public Request find(String token) {
		return pending != null && pending.token.equals(token) ? pending : null;
	}

	public void cancel(String token) {
		if (find(token) != null) pending = null;
	}

	public boolean complete(Result result) {
		if (pending != result.request) return false;
		pending = null;
		ready = result;
		return true;
	}

	public Result consume(String owner, Page page) {
		Result result = ready;
		if (result != null && result.request.owner.equals(owner)) {
			ready = null;
			Request request = result.request;
			if ("soyjak".equals(page.chanName) && page.content == request.content
					&& Objects.equals(page.boardName, request.board) && Objects.equals(page.threadNumber, request.thread)) {
				return result;
			}
		}
		return null;
	}
}
