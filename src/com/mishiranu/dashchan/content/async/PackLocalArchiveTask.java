package com.mishiranu.dashchan.content.async;

import com.mishiranu.dashchan.content.LocalArchiveManager;
import java.util.List;

public class PackLocalArchiveTask extends ExecutorTask<Void, Boolean> {
	public interface Callback {
		void onPackLocalArchiveComplete(boolean success);
	}

	private final Callback callback;
	private final String archiveName;
	private final List<String> files;
	private final List<String> thumbnails;

	public PackLocalArchiveTask(Callback callback, String archiveName,
			List<String> files, List<String> thumbnails) {
		this.callback = callback;
		this.archiveName = archiveName;
		this.files = files;
		this.thumbnails = thumbnails;
	}

	@Override
	protected Boolean run() {
		return LocalArchiveManager.createZip(archiveName, files, thumbnails);
	}

	@Override
	protected void onComplete(Boolean result) {
		callback.onPackLocalArchiveComplete(Boolean.TRUE.equals(result));
	}
}
