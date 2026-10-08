package com.mishiranu.dashchan.content.async;

import androidx.lifecycle.MutableLiveData;
import chan.content.Chan;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/** One dialog-scoped preparation, not the later download/ZIP operation. Holds no UI dependencies. */
public class LocalArchiveViewModel extends TaskViewModel<SendLocalArchiveTask,
		LocalArchiveViewModel.PreparationResult> implements SendLocalArchiveTask.Callback {
	public static final class Request {
		public final String chanName;
		public final String boardName;
		public final String threadNumber;
		public final String threadTitle;
		public final List<Post> posts;
		public final boolean saveThumbnails;
		public final boolean saveFiles;
		public final boolean createZip;

		public Request(String chanName, String boardName, String threadNumber, String threadTitle,
				Collection<Post> posts, boolean saveThumbnails, boolean saveFiles, boolean createZip) {
			this.chanName = chanName;
			this.boardName = boardName;
			this.threadNumber = threadNumber;
			this.threadTitle = threadTitle;
			// PostsPage already supplies a snapshot. Freeze its order without retaining a mutable collection.
			this.posts = Collections.unmodifiableList(new ArrayList<>(posts));
			this.saveThumbnails = saveThumbnails;
			this.saveFiles = saveFiles;
			this.createZip = createZip;
		}
	}

	public static final class PreparationResult {
		public enum Status {PREPARED, ERROR}
		public final Status status;
		public final SendLocalArchiveTask.DownloadResult downloadResult;

		private PreparationResult(SendLocalArchiveTask.DownloadResult downloadResult) {
			status = downloadResult != null ? Status.PREPARED : Status.ERROR;
			this.downloadResult = downloadResult;
		}
	}

	interface TaskFactory {
		SendLocalArchiveTask create(SendLocalArchiveTask.Callback callback, Request request);
	}

	private final TaskFactory taskFactory;
	private final Executor executor;
	private boolean started;
	public final MutableLiveData<Integer> progress = new MutableLiveData<>();

	public LocalArchiveViewModel() {
		this((callback, request) -> new SendLocalArchiveTask(callback, Chan.get(request.chanName),
				request.boardName, request.threadNumber, request.threadTitle, request.posts,
				request.saveThumbnails, request.saveFiles, request.createZip), ConcurrentUtils.PARALLEL_EXECUTOR);
	}

	// Package-private launch seam: tests exercise this owner without filesystem/network preparation.
	LocalArchiveViewModel(TaskFactory taskFactory, Executor executor) {
		this.taskFactory = taskFactory;
		this.executor = executor;
	}

	public void startIfNeeded(Request request) {
		if (started || hasTaskOrValue()) return;
		SendLocalArchiveTask task = taskFactory.create(this, request);
		started = true;
		attach(task);
		task.execute(executor);
	}

	@Override
	public void onLocalArchivationProgressUpdate(int handledPostsCount) {
		progress.setValue(handledPostsCount);
	}

	@Override
	public void onLocalArchivationComplete(SendLocalArchiveTask.DownloadResult result) {
		handleResult(new PreparationResult(result));
	}
}
