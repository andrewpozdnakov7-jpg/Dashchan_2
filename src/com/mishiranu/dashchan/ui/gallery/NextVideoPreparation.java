package com.mishiranu.dashchan.ui.gallery;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.media.VideoPlayer;
import com.mishiranu.dashchan.media.VideoPreparationPolicy;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** UI-thread owner of one speculative, silent decoder. Rapid requests coalesce, never queue per swipe. */
final class NextVideoPreparation {
	private static final Executor EXECUTOR = ConcurrentUtils.newSingleThreadPool(10000, "NextVideoPreparation", null);
	private final boolean lowRam;
	private VideoPreparationIdentity wanted, attempted, readyIdentity;
	private VideoPlayer.PreparedPlayback ready;
	private Task running;
	private Runnable onReady;
	private int readyChecks;
	private final Runnable readyProbe = this::publishWhenReady;

	private static final class Task {
		final VideoPreparationIdentity identity;
		final AtomicBoolean cancelled = new AtomicBoolean();
		Task(VideoPreparationIdentity identity) { this.identity = identity; }
	}

	NextVideoPreparation(Context context) {
		ActivityManager manager = (ActivityManager) context.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
		lowRam = manager == null || manager.isLowRamDevice();
	}

	static VideoPreparationIdentity identity(Uri uri, File file) {
		return new VideoPreparationIdentity(uri.toString(), file.getAbsolutePath(), file.length(), file.lastModified(),
				VideoPositionMemory.requestedPosition(file), Preferences.isVideoSeekAnyFrame(), Preferences.isHardwareVideoAcceleration());
	}

	void setOnReady(Runnable callback) { onReady = callback; }
	boolean allowsPageFrames() { return !lowRam; }

	VideoPreparationIdentity readyIdentity() {
		return readyIdentity != null && isReady(Uri.parse(readyIdentity.resource)) ? readyIdentity : null;
	}

	VideoPageFrame copyReadyFrame() {
		if (ready == null || !ready.isReady()) return null;
		Bitmap copy = ready.copyFrame();
		if (copy == null) return null;
		android.graphics.Point size = ready.getPlayer().getDimensions();
		return new VideoPageFrame(copy, size.x, size.y);
	}

	void request(Uri uri, File file) {
		if (lowRam || !VideoPlayer.isLoaded() || !Preferences.isUseVideoPlayer() || uri == null
				|| file == null || !file.isFile() || !VideoPreparationPolicy.fileAllowed(file.length(), lowRam)) { stop(); return; }
		VideoPreparationIdentity next = identity(uri, file);
		if (next.matches(wanted)) return;
		stop(); wanted = next; startPending();
	}

	/** A page switch may retain the prepared target, but never retain a decoder for a different page. */
	void retainFor(Uri selected) {
		if (selected == null || wanted == null || !wanted.resource.equals(selected.toString())) stop();
	}

	void stop() {
		ConcurrentUtils.HANDLER.removeCallbacks(readyProbe);
		wanted = null; attempted = null;
		if (running != null) running.cancelled.set(true);
		if (ready != null) dispose(ready);
		ready = null; readyIdentity = null;
	}

	boolean isReady(Uri uri) {
		if (uri == null || ready == null || readyIdentity == null || !readyIdentity.resource.equals(uri.toString())) return false;
		File file = new File(readyIdentity.path);
		return readyIdentity.matches(identity(uri, file)) && ready.isReady();
	}

	VideoPlayer.PreparedPlayback take(Uri uri, File file, boolean reload) {
		VideoPlayer.PreparedPlayback result = null;
		if (!reload && uri != null && file != null && ready != null && readyIdentity.matches(identity(uri, file))) {
			VideoPlayer player = ready.getPlayer();
			if (ready.isReady() && player != null && ready.position == VideoPreparationIdentity.startPosition(
					VideoPositionMemory.requestedPosition(file), player.getDuration())) {
				result = ready; ready = null; readyIdentity = null;
			}
		}
		stop();
		VideoDiagnostics.recordUi("gallery next_video_preparation phase=take ready=" + (result != null));
		return result;
	}

	private void startPending() {
		if (running != null || wanted == null || wanted.matches(attempted)) return;
		Task task = new Task(wanted); running = task; attempted = wanted;
		VideoDiagnostics.recordUi("gallery next_video_preparation phase=started");
		EXECUTOR.execute(() -> {
			long started = SystemClock.elapsedRealtime();
			VideoPlayer.PreparedPlayback result = null;
			String outcome = "unavailable";
			try {
				if (!task.cancelled.get() && unchanged(task.identity)) {
					result = VideoPlayer.preparePlayback(new File(task.identity.path), task.identity.seekAnyFrame, task.identity.hardware,
							task.identity.requestedPosition, task.cancelled::get);
				}
				outcome = result != null ? "decoded" : "unavailable";
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt(); outcome = "interrupted";
			} catch (IOException | RuntimeException | LinkageError | OutOfMemoryError e) {
				outcome = e.getClass().getSimpleName();
			}
			if (result != null && task.cancelled.get()) { closeSafely(result); result = null; outcome = "cancelled"; }
			VideoDiagnostics.recordUi("gallery next_video_preparation phase=worker_done outcome=" + outcome
					+ " elapsed_ms=" + (SystemClock.elapsedRealtime() - started));
			VideoPlayer.PreparedPlayback completed = result;
			ConcurrentUtils.HANDLER.post(() -> complete(task, completed));
		});
	}

	private void complete(Task task, VideoPlayer.PreparedPlayback result) {
		if (running == task) running = null;
		if (!task.cancelled.get() && task.identity.matches(wanted) && unchanged(task.identity) && result != null) {
			ready = result; readyIdentity = task.identity;
			VideoDiagnostics.recordUi("gallery next_video_preparation phase=" + (ready.isReady() ? "ready" : "awaiting_seek_commit"));
			readyChecks = 30;
			publishWhenReady();
		} else if (result != null) dispose(result);
		startPending();
	}

	private void publishWhenReady() {
		if (ready == null || readyIdentity == null || !readyIdentity.matches(wanted)) return;
		if (ready.isReady()) {
			if (onReady != null) onReady.run();
		} else if (readyChecks-- > 0) {
			ConcurrentUtils.HANDLER.postDelayed(readyProbe, 16L);
		}
	}

	private static boolean unchanged(VideoPreparationIdentity identity) {
		File file = new File(identity.path);
		return file.isFile() && file.length() == identity.length && file.lastModified() == identity.modified;
	}

	static void dispose(VideoPlayer.PreparedPlayback prepared) {
		// Native destroy may join worker threads: never wait for it during navigation.
		EXECUTOR.execute(() -> closeSafely(prepared));
	}

	private static void closeSafely(VideoPlayer.PreparedPlayback prepared) {
		try { prepared.close(); }
		catch (RuntimeException | LinkageError e) {
			VideoDiagnostics.recordUi("gallery next_video_preparation phase=dispose_failed error=" + e.getClass().getSimpleName());
		}
	}
}
