package com.mishiranu.dashchan.content.async;

import android.util.Pair;
import chan.content.Chan;
import chan.content.ChanPerformer;
import chan.content.ExtensionException;
import chan.content.InvalidResponseException;
import chan.content.RedirectException;
import chan.content.ThreadRedirectException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

public class ReadThreadPreviewTask extends HttpHolderTask<Void, Pair<ErrorItem, List<PostItem>>> {
	private static final int PREVIEW_POSTS_COUNT = 5;

	private final Callback callback;
	private final Chan chan;
	private final String boardName;
	private final String threadNumber;

	public interface Callback {
		void onReadThreadPreviewSuccess(List<PostItem> postItems);
		void onReadThreadPreviewFail(ErrorItem errorItem);
	}

	public ReadThreadPreviewTask(Callback callback, Chan chan, String boardName, String threadNumber) {
		super(chan);
		this.callback = callback;
		this.chan = chan;
		this.boardName = boardName;
		this.threadNumber = threadNumber;
	}

	@Override
	protected Pair<ErrorItem, List<PostItem>> run(HttpHolder holder) {
		try {
			ChanPerformer.ReadPostsResult result = chan.performer.safe().onReadPosts(new ChanPerformer.ReadPostsData(
					chan.name, boardName, threadNumber, null, true, false, holder, null));
			if (result == null || result.posts.size() < 2) {
				return new Pair<>(null, Collections.emptyList());
			}
			TreeMap<PostNumber, Post> posts = new TreeMap<>();
			for (Post post : result.posts) posts.put(post.number, post);
			if (posts.size() < 2) return new Pair<>(null, Collections.emptyList());
			PostNumber originalPostNumber = posts.firstKey();
			ArrayList<Post> replies = new ArrayList<>(posts.values());
			replies.remove(0);
			int start = Math.max(0, replies.size() - PREVIEW_POSTS_COUNT);
			ArrayList<PostItem> postItems = new ArrayList<>(replies.size() - start);
			for (int i = start; i < replies.size(); i++) {
				PostItem postItem = PostItem.createPost(replies.get(i), chan,
						boardName, threadNumber, originalPostNumber);
				postItem.setOrdinalIndex(i + 1);
				postItems.add(postItem);
			}
			return new Pair<>(null, postItems);
		} catch (HttpException e) {
			return new Pair<>(e.getErrorItemAndHandle(), null);
		} catch (ExtensionException | InvalidResponseException e) {
			return new Pair<>(e.getErrorItemAndHandle(), null);
		} catch (RedirectException | ThreadRedirectException e) {
			return new Pair<>(new ErrorItem(ErrorItem.Type.INVALID_DATA_FORMAT), null);
		} finally {
			chan.configuration.commit();
		}
	}

	@Override
	protected void onComplete(Pair<ErrorItem, List<PostItem>> result) {
		if (result.second != null) {
			callback.onReadThreadPreviewSuccess(result.second);
		} else {
			callback.onReadThreadPreviewFail(result.first);
		}
	}
}
