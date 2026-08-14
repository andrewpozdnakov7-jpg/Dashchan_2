package com.mishiranu.dashchan.content.async;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.net.Uri;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.text.ParseException;
import com.mishiranu.dashchan.content.PostsWindowCache;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.database.CommonDatabase;
import com.mishiranu.dashchan.content.database.PostsDatabase;
import com.mishiranu.dashchan.content.database.ThreadsDatabase;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.util.ThreadOpenDiagnostics;
import java.util.ArrayList;
import java.util.HashMap;

public class ReadPostsWindowTask extends ExecutorTask<Void, ReadPostsWindowTask.Result> {
	public static final class Result {
		public final PostsWindowCache.Window window;
		public final PostsDatabase.Flags flags;
		public final ThreadsDatabase.StateExtra stateExtra;
		public final Uri archivedThreadUri;
		public final int uniquePosters;

		private Result(PostsWindowCache.Window window, PostsDatabase.Flags flags,
				ThreadsDatabase.StateExtra stateExtra, Uri archivedThreadUri, int uniquePosters) {
			this.window = window;
			this.flags = flags;
			this.stateExtra = stateExtra;
			this.archivedThreadUri = archivedThreadUri;
			this.uniquePosters = uniquePosters;
		}
	}

	public interface Callback {
		void onReadPostsWindowComplete(ReadPostsWindowTask task, Result result);
	}

	private final Callback callback;
	private final Chan chan;
	private final String boardName;
	private final String threadNumber;
	private final PostNumber anchorPostNumber;
	private final int requestedPosition;
	private final boolean force;
	private final CancellationSignal signal = new CancellationSignal();
	private final ThreadOpenDiagnostics.Operation diagnosticOperation;
	private boolean cacheHit;

	public ReadPostsWindowTask(Callback callback, Chan chan, String boardName, String threadNumber,
			PostNumber anchorPostNumber, int requestedPosition, boolean force, int diagnosticSessionId) {
		this.callback = callback;
		this.chan = chan;
		this.boardName = boardName;
		this.threadNumber = threadNumber;
		this.anchorPostNumber = anchorPostNumber;
		this.requestedPosition = requestedPosition;
		this.force = force;
		diagnosticOperation = ThreadOpenDiagnostics.beginOperation(diagnosticSessionId, "window_query");
	}

	@Override
	protected Result run() {
		PagesDatabase database = PagesDatabase.getInstance();
		PagesDatabase.ThreadKey threadKey = new PagesDatabase.ThreadKey(chan.name, boardName, threadNumber);
		PostsWindowCache cache = PostsWindowCache.getInstance();
		if (!force) {
			PostsWindowCache.Window cached = cache.get(threadKey);
			if (cached != null && (requestedPosition >= 0 ? cached.containsPosition(requestedPosition)
					: anchorPostNumber != null ? cached.postItems.containsKey(anchorPostNumber) : true)) {
				cacheHit = true;
				return buildResult(database, threadKey, cached);
			}
		}

		PagesDatabase.PostWindow source;
		try {
			source = database.getPostWindow(threadKey, anchorPostNumber, requestedPosition,
					PostsWindowCache.WINDOW_SIZE, signal);
		} catch (ParseException e) {
			e.printStackTrace();
			return null;
		} catch (OperationCanceledException e) {
			return null;
		}
		ArrayList<PostNumber> postNumbers = new ArrayList<>(source.posts.size());
		HashMap<PostNumber, PostItem> postItems = new HashMap<>(source.posts.size());
		int ordinalIndex = source.startOrdinalIndex;
		for (Post post : source.posts) {
			PostItem postItem = PostItem.createPost(post, chan, boardName, threadNumber,
					source.originalPostNumber);
			postItem.setOrdinalIndex(post.deleted ? PostItem.ORDINAL_INDEX_DELETED : ordinalIndex++);
			postNumbers.add(post.number);
			postItems.put(post.number, postItem);
		}
		for (PostItem postItem : postItems.values()) {
			for (PostNumber referenceTo : postItem.getReferencesTo()) {
				PostItem referenced = postItems.get(referenceTo);
				if (referenced != null) {
					referenced.addReferenceFrom(postItem.getPostNumber());
				}
			}
		}
		PostsWindowCache.Window window = new PostsWindowCache.Window(threadKey,
				database.getCacheState(threadKey), source.totalCount, source.startPosition,
				postNumbers, postItems, source.estimatedMemoryBytes);
		cache.put(window);
		return buildResult(database, threadKey, window);
	}

	private Result buildResult(PagesDatabase database, PagesDatabase.ThreadKey threadKey,
			PostsWindowCache.Window window) {
		PostsDatabase.Flags flags = CommonDatabase.getInstance().getPosts()
				.getFlags(chan.name, boardName, threadNumber);
		ThreadsDatabase.StateExtra stateExtra = CommonDatabase.getInstance().getThreads()
				.getStateExtra(chan.name, boardName, threadNumber);
		PagesDatabase.Meta meta = database.getMeta(threadKey,
				chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE));
		return new Result(window, flags, stateExtra, meta != null ? meta.archivedThreadUri : null,
				meta != null ? meta.uniquePosters : 0);
	}

	@Override
	protected void onCancel(Result result) {
		finishDiagnostics(result, "cancelled");
		callback.onReadPostsWindowComplete(this, null);
	}

	@Override
	protected void onComplete(Result result) {
		finishDiagnostics(result, result != null ? cacheHit ? "cache" : "database" : "failed");
		callback.onReadPostsWindowComplete(this, result);
	}

	private void finishDiagnostics(Result result, String status) {
		PostsWindowCache.Window window = result != null ? result.window : null;
		ThreadOpenDiagnostics.endOperation(diagnosticOperation, status,
				window != null ? window.postNumbers.size() : -1, window != null ? window.totalCount : -1);
	}

	@Override
	public void cancel() {
		super.cancel();
		try {
			signal.cancel();
		} catch (Exception e) {
			// Ignore cancellation races.
		}
	}
}
