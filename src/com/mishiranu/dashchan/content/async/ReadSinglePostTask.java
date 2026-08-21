package com.mishiranu.dashchan.content.async;

import android.util.Pair;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import chan.content.ExtensionException;
import chan.content.InvalidResponseException;
import chan.content.RedirectException;
import chan.content.ThreadRedirectException;
import chan.content.model.SinglePost;
import chan.http.HttpException;
import chan.http.HttpHolder;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.net.HttpURLConnection;

public class ReadSinglePostTask extends HttpHolderTask<Void, Pair<ErrorItem, PostItem>> {
	private final Callback callback;
	private final Chan chan;
	private final String boardName;
	private final String threadNumber;
	private final PostNumber postNumber;

	public interface Callback {
		void onReadSinglePostSuccess(PostItem postItem);
		void onReadSinglePostFail(ErrorItem errorItem);
	}

	public ReadSinglePostTask(Callback callback, Chan chan,
			String boardName, String threadNumber, PostNumber postNumber) {
		super(chan);
		this.callback = callback;
		this.chan = chan;
		this.boardName = boardName;
		this.threadNumber = threadNumber;
		this.postNumber = postNumber;
	}

	@Override
	protected Pair<ErrorItem, PostItem> run(HttpHolder holder) {
		try {
			SinglePost post;
			if (chan.configuration.getOption(ChanConfiguration.OPTION_READ_SINGLE_POST)) {
				String postNumber;
				if (this.postNumber != null) {
					postNumber = this.postNumber.toString();
				} else {
					postNumber = threadNumber;
				}
				ChanPerformer.ReadSinglePostResult result = chan.performer.safe().onReadSinglePost(new ChanPerformer
						.ReadSinglePostData(boardName, postNumber, holder));
				post = result != null ? result.post : null;
			} else {
				post = readPostFromThread(holder);
			}
			if (post == null) {
				throw HttpException.createNotFoundException();
			}
			return new Pair<>(null, PostItem.createPost(post.post, chan,
					boardName, post.threadNumber, post.originalPostNumber));
		} catch (HttpException e) {
			ErrorItem errorItem = e.getErrorItemAndHandle();
			if (errorItem.httpResponseCode == HttpURLConnection.HTTP_NOT_FOUND ||
					errorItem.httpResponseCode == HttpURLConnection.HTTP_GONE) {
				errorItem = new ErrorItem(ErrorItem.Type.POST_NOT_FOUND);
			}
			return new Pair<>(errorItem, null);
		} catch (RedirectException | ThreadRedirectException e) {
			return new Pair<>(new ErrorItem(ErrorItem.Type.POST_NOT_FOUND), null);
		} catch (ExtensionException | InvalidResponseException e) {
			return new Pair<>(e.getErrorItemAndHandle(), null);
		} finally {
			chan.configuration.commit();
		}
	}

	private SinglePost readPostFromThread(HttpHolder holder) throws ExtensionException, HttpException,
			InvalidResponseException, RedirectException, ThreadRedirectException {
		ChanPerformer.ReadPostsResult result = chan.performer.safe().onReadPosts(new ChanPerformer.ReadPostsData(
				chan.name, boardName, threadNumber, null, false, false, holder, null));
		if (result == null || result.posts.isEmpty()) {
			return null;
		}
		PostNumber originalPostNumber = null;
		Post targetPost = null;
		for (Post post : result.posts) {
			if (originalPostNumber == null || post.number.compareTo(originalPostNumber) < 0) {
				originalPostNumber = post.number;
			}
			if (post.number.equals(postNumber)) {
				targetPost = post;
			}
		}
		return targetPost != null ? new SinglePost(targetPost, threadNumber, originalPostNumber) : null;
	}

	@Override
	protected void onComplete(Pair<ErrorItem, PostItem> result) {
		if (result.second != null) {
			callback.onReadSinglePostSuccess(result.second);
		} else {
			callback.onReadSinglePostFail(result.first);
		}
	}
}
