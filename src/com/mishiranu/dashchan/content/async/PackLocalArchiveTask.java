package com.mishiranu.dashchan.content.async;

import chan.util.DataFile;
import com.mishiranu.dashchan.content.LocalArchiveManager;
import java.util.List;

public class PackLocalArchiveTask extends ExecutorTask<Void, Boolean> {
	public interface Callback {
		void onPackLocalArchiveComplete(boolean success);
	}

	private final Callback callback;
	private final DataFile archiveDirectory;
	private final String archiveName;
	private final String resourceDirectory;
	private final List<String> files;
	private final List<String> thumbnails;

	public PackLocalArchiveTask(Callback callback, DataFile archiveDirectory, String archiveName, String resourceDirectory,
			List<String> files, List<String> thumbnails) {
		this.callback = callback;
		this.archiveDirectory = archiveDirectory;
		this.archiveName = archiveName;
		this.resourceDirectory = resourceDirectory;
		this.files = files;
		this.thumbnails = thumbnails;
	}

	@Override
	protected Boolean run() {
		try {
			return LocalArchiveManager.createZip(archiveDirectory, archiveName, resourceDirectory, files, thumbnails);
		} catch (RuntimeException e) {
			// Revoked storage access must complete the operation and release its update lease.
			com.mishiranu.dashchan.media.VideoDiagnostics.recordUi("archive_update stage=pack_runtime_failed type=" + e.getClass().getSimpleName());
			return false;
		}
	}

	@Override
	protected void onComplete(Boolean result) {
		callback.onPackLocalArchiveComplete(Boolean.TRUE.equals(result));
	}
}
