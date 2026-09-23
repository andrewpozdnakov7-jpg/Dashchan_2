package com.mishiranu.dashchan.ui.gallery;

import android.net.Uri;
import chan.content.Chan;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.AdvancedPreferences;
import com.mishiranu.dashchan.content.async.ReadVideoTask;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.media.VideoPlayer;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.io.File;
import java.io.IOException;

// Owns network/range requests independently of either gallery or PiP views.
// State and listeners are accessed on the main thread; only native init runs in a worker.
final class VideoDownloadSession implements ReadVideoTask.Callback, VideoPlayer.RangeCallback {
	interface Listener {
		void onInitialized(VideoDownloadSession session);
		void onDownloadChanged(VideoDownloadSession session);
		void onDownloadError(VideoDownloadSession session, ErrorItem error);
	}

	private final VideoPlayer player;
	private final String chanName;
	private final Uri uri;
	private final File sourceFile;
	private Listener listener;
	private ReadVideoTask downloadTask;
	private ReadVideoTask rangeTask;
	private boolean allowRangeRequests;
	private boolean cancelled;
	private boolean initialized;
	private boolean complete;
	private long progress;
	private long total = -1L;
	private ErrorItem error;

	VideoDownloadSession(VideoPlayer player, String chanName, Uri uri, File sourceFile) {
		this.player = player;
		this.chanName = chanName;
		this.uri = uri;
		this.sourceFile = sourceFile;
		allowRangeRequests = !AdvancedPreferences.isSingleConnection(chanName);
	}

	void start(Listener listener) {
		this.listener = listener;
		downloadTask = new ReadVideoTask(this, Chan.getPreferred(chanName, uri), uri, 0);
		downloadTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
	}

	void setListener(Listener listener) { this.listener = listener; }
	boolean isComplete() { return complete; }
	boolean isReady() { return initialized && !cancelled && error == null; }
	long getProgress() { return progress; }
	long getTotal() { return total; }
	ErrorItem getError() { return error; }

	void cancel() {
		cancelled = true;
		listener = null;
		cancelRequests();
		VideoDiagnostics.recordUi("video download_session cancelled complete=" + complete);
	}

	private void cancelRequests() {
		if (downloadTask != null) {
			downloadTask.cancel();
			downloadTask = null;
		}
		if (rangeTask != null) {
			rangeTask.cancel();
			rangeTask = null;
		}
	}

	private void fail(ErrorItem error) {
		this.error = error;
		cancelRequests();
		VideoDiagnostics.recordUi("video download_session failed type=" + error.type);
		if (listener != null) listener.onDownloadError(this, error);
	}

	@Override
	public void onReadVideoInit(File partialFile) {
		if (cancelled || error != null) return;
		new Thread(() -> {
			boolean success;
			try {
				// The completed download may already have renamed the partial file.
				player.init(partialFile.isFile() ? partialFile : sourceFile, this);
				success = true;
			} catch (IOException e) {
				success = false;
			}
			boolean successFinal = success;
			ConcurrentUtils.HANDLER.post(() -> {
				if (cancelled || error != null) return;
				if (successFinal) {
					initialized = true;
					player.setDownloadRange(progress, total);
					if (listener != null) listener.onInitialized(this);
				} else if (downloadTask == null || !downloadTask.isError()) {
					fail(new ErrorItem(R.string.playback_error));
				}
			});
		}, "VideoDownloadInit").start();
	}

	@Override
	public void onReadReady() {
		// A small download can finish before the init worker opens the decoder.
		// Replay its range before native init waits for bytes, not after init returns.
		ConcurrentUtils.HANDLER.post(() -> {
			if (!cancelled && error == null) player.setDownloadRange(progress, total);
		});
	}

	@Override
	public void onReadVideoProgressUpdate(long progress, long progressMax) {
		if (cancelled || error != null || complete) return;
		this.progress = progress;
		total = progressMax;
		player.setDownloadRange(progress, progressMax);
		if (listener != null) listener.onDownloadChanged(this);
	}

	@Override
	public void onReadVideoRangeUpdate(long start, long end) {
		if (!cancelled && error == null && !complete) player.setPartRange(start, end);
	}

	@Override
	public void onReadVideoSuccess(boolean partial, File file) {
		if (cancelled || error != null) return;
		if (partial) {
			rangeTask = null;
		} else {
			downloadTask = null;
			complete = true;
			progress = total = file.length();
			if (rangeTask != null) {
				rangeTask.cancel();
				rangeTask = null;
			}
			player.setDownloadRange(total, total);
			VideoDiagnostics.recordUi("video download_session complete bytes=" + total);
			if (listener != null) listener.onDownloadChanged(this);
		}
	}

	@Override
	public void onReadVideoFail(boolean partial, ErrorItem error, boolean disallowRangeRequests) {
		if (cancelled || this.error != null || complete) return;
		if (partial) {
			rangeTask = null;
			if (disallowRangeRequests) allowRangeRequests = false;
		} else {
			fail(error);
		}
	}

	@Override
	public void requestPartFromPosition(long start) {
		if (rangeTask != null) {
			rangeTask.cancel();
			rangeTask = null;
		}
		if (!cancelled && error == null && !complete && allowRangeRequests && start > 0) {
			rangeTask = new ReadVideoTask(this, Chan.getPreferred(chanName, uri), uri, start);
			rangeTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
		}
	}
}
