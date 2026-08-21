package com.mishiranu.dashchan.content.async;

import chan.content.Chan;
import chan.content.ChanPerformer;
import chan.content.ExtensionException;
import chan.content.InvalidResponseException;
import chan.content.RedirectException;
import chan.content.ThreadRedirectException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.storage.MyPostsStorage;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.List;

public class ReadMyPostsTask extends HttpHolderTask<Void, ReadMyPostsTask.Result> {
	public interface Callback {
		void onReadMyPostsComplete(MyPostsStorage.ThreadKey key, Result result);
	}

	public static final class Result {
		public final List<Post> posts;
		public final ErrorItem errorItem;
		public final boolean threadDeleted;

		private Result(List<Post> posts, ErrorItem errorItem, boolean threadDeleted) {
			this.posts = posts;
			this.errorItem = errorItem;
			this.threadDeleted = threadDeleted;
		}
	}

	private final Callback callback;
	private final Chan chan;
	private final MyPostsStorage.ThreadKey key;

	public ReadMyPostsTask(Callback callback, MyPostsStorage.ThreadKey key) {
		super(Chan.get(key.chanName));
		this.callback = callback;
		this.chan = Chan.get(key.chanName);
		this.key = key;
	}

	@Override
	protected Result run(HttpHolder holder) {
		try {
			ChanPerformer.ReadPostsResult result = chan.performer.safe().onReadPosts(
					new ChanPerformer.ReadPostsData(chan.name, key.boardName, key.threadNumber,
							null, false, false, holder, null));
			List<Post> posts = result != null ? result.posts : Collections.emptyList();
			if (posts.isEmpty()) {
				return new Result(null, new ErrorItem(ErrorItem.Type.EMPTY_RESPONSE), false);
			}
			return new Result(posts, null, false);
		} catch (HttpException e) {
			ErrorItem errorItem = e.getErrorItemAndHandle();
			boolean deleted = errorItem.httpResponseCode == HttpURLConnection.HTTP_NOT_FOUND
					|| errorItem.httpResponseCode == HttpURLConnection.HTTP_GONE;
			return new Result(null, errorItem, deleted);
		} catch (RedirectException | ThreadRedirectException e) {
			return new Result(null, new ErrorItem(ErrorItem.Type.THREAD_NOT_EXISTS), true);
		} catch (ExtensionException | InvalidResponseException e) {
			return new Result(null, e.getErrorItemAndHandle(), false);
		} finally {
			chan.configuration.commit();
		}
	}

	@Override
	protected void onComplete(Result result) {
		callback.onReadMyPostsComplete(key, result);
	}
}
