package com.mishiranu.dashchan.ui.gallery;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.MainThread;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.media.VideoPlayer;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.UUID;

/**
 * Same-process pending requests for gallery/PiP playback transfer.
 *
 * Entry requests lend gallery resources until the PiP Activity takes them; replacing/cancelling an entry
 * recycles only its preview. Return requests own playback/download/handoff after the Activity detaches them.
 * Consume a matching request before adoption/cleanup so a late token cannot take a newer request.
 * Call resource operations on the main thread. The lock protects publication, not Android lifecycle policy.
 * Window geometry, focus decisions, activity launch and retry timing remain with VideoPipActivity.
 */
@MainThread
final class PipTransferRegistry {
	private static final Object TRANSFER_LOCK = new Object();
	private static PendingTransfer pendingTransfer;
	private static PendingGalleryReturn pendingGalleryReturn;
	private static final long RETURN_PAUSE_DELAY_MS = 1500L;
	private static final long RETURN_EXPIRY_DELAY_MS = 30000L;

	private PipTransferRegistry() {}

	static final class PendingTransfer {
		public final VideoUnit.PictureInPictureSource source;
		public final VideoPlayer player;
		public final String filePath;
		public final Bitmap previewFrame;
		public final VideoPipActivity.GalleryRestoreData galleryRestoreData;
		public final VideoDownloadSession downloadSession;

		PendingTransfer(VideoUnit.PictureInPictureSource source, VideoPlayer player, String filePath, Bitmap previewFrame,
				VideoPipActivity.GalleryRestoreData galleryRestoreData, VideoDownloadSession downloadSession) {
			this.source = source;
			this.player = player;
			this.filePath = filePath;
			this.previewFrame = previewFrame;
			this.galleryRestoreData = galleryRestoreData;
			this.downloadSession = downloadSession;
		}

		private void recyclePreviewFrame() {
			if (previewFrame != null && !previewFrame.isRecycled()) {
				previewFrame.recycle();
			}
		}
	}

	static final class PendingGalleryReturn {
		final String token = UUID.randomUUID().toString();
		final long started = SystemClock.elapsedRealtime();
		final WeakReference<VideoPipActivity> owner;
		final Handler handler = new Handler(Looper.getMainLooper());
		final Runnable timeout = this::pauseForLateTarget;
		final Runnable expiry = () -> cancelPendingGalleryReturn(token, "target_expired");
		VideoUnit target;
		boolean disposed;
		final VideoPipActivity.GalleryRestoreData data;
		final VideoPlayer player;
		final File sourceFile;
		final VideoDownloadSession downloadSession;
		final long position;
		final int playbackSpeed;
		final boolean muted;
		final boolean playing;
		final Bitmap previewFrame;
		final VideoPlaybackHandoff handoff;

		PendingGalleryReturn(VideoPipActivity owner, VideoPipActivity.GalleryRestoreData data, VideoPlayer player,
				File sourceFile, long position,
				int playbackSpeed, boolean muted, boolean playing, VideoDownloadSession downloadSession,
				Bitmap previewFrame, VideoPlaybackHandoff handoff) {
			this.handoff = handoff;
			this.owner = new WeakReference<>(owner);
			this.previewFrame = previewFrame;
			this.data = data;
			this.player = player;
			this.sourceFile = sourceFile;
			this.downloadSession = downloadSession;
			this.position = position;
			this.playbackSpeed = playbackSpeed;
			this.muted = muted;
			this.playing = playing;
		}

		void pauseForLateTarget() {
			if (disposed) return;
			// Bound background playback, not the lifetime of a recoverable request.
			// The resumed host may still adopt this paused player after recreation.
			handoff.stop("target_wait_paused");
			VideoDiagnostics.recordUi("pip_return target_wait_paused bound=" + (target != null)
					+ " elapsed_ms=" + (SystemClock.elapsedRealtime() - started));
		}

		void finishPreparation() {
			handler.removeCallbacks(timeout);
			handler.removeCallbacks(expiry);
			if (target != null) target.clearPendingPictureInPictureRestore(token);
			target = null;
			VideoPipActivity activity = owner.get();
			if (activity != null && !activity.isDestroyed() && !activity.isFinishing()) activity.finish();
			owner.clear();
		}

		void destroyPlayer() {
			if (disposed) return;
			disposed = true;
			VideoPositionMemory.save(player, false);
			finishPreparation();
			handoff.stop("pending_return_disposed");
			if (downloadSession != null) downloadSession.cancel();
			player.setVideoViewFrameCallback(null);
			player.setListener(null);
			player.setPlaying(false);
			player.releaseVideoViewAndDestroyAsync();
		}
	}

	static void registerTransfer(VideoUnit.PictureInPictureSource source, VideoPlayer player, File file,
			Bitmap previewFrame, VideoPipActivity.GalleryRestoreData data, VideoDownloadSession downloadSession) {
		synchronized (TRANSFER_LOCK) {
			if (pendingTransfer != null) {
				pendingTransfer.recyclePreviewFrame();
			}
			pendingTransfer = new PendingTransfer(source, player, file.getAbsolutePath(), previewFrame,
					data, downloadSession);
		}
	}

	static void cancelPendingTransfer(VideoUnit.PictureInPictureSource source, VideoPlayer player) {
		synchronized (TRANSFER_LOCK) {
			if (pendingTransfer != null && pendingTransfer.source == source && pendingTransfer.player == player) {
				pendingTransfer.recyclePreviewFrame();
				pendingTransfer = null;
			}
		}
	}

	static PendingTransfer takePendingTransfer(String filePath) {
		synchronized (TRANSFER_LOCK) {
			if (pendingTransfer != null && pendingTransfer.filePath.equals(filePath)) {
				PendingTransfer transfer = pendingTransfer;
				pendingTransfer = null;
				return transfer;
			}
		}
		return null;
	}

	static String getPendingGalleryReturnToken() {
		synchronized (TRANSFER_LOCK) {
			return pendingGalleryReturn != null ? pendingGalleryReturn.token : null;
		}
	}

	static Bitmap getPendingGalleryReturnPreview(String token) {
		synchronized (TRANSFER_LOCK) {
			PendingGalleryReturn pending = pendingGalleryReturn;
			Bitmap frame = pending != null && pending.token.equals(token) ? pending.previewFrame : null;
			return frame != null && !frame.isRecycled() ? frame : null;
		}
	}

	static GalleryOverlay createPendingGalleryReturnOverlay() {
		synchronized (TRANSFER_LOCK) {
			PendingGalleryReturn pending = pendingGalleryReturn;
			if (pending == null) {
				return null;
			}
			VideoDiagnostics.recordUi("pip_return create_target elapsed_ms="
					+ (SystemClock.elapsedRealtime() - pending.started));
			return GalleryOverlay.createForPictureInPictureRestore(pending.data, pending.token);
		}
	}

	static boolean restorePendingGalleryPlayer(VideoUnit target, String token) {
		if (token == null) {
			return false;
		}
		PendingGalleryReturn pending;
		synchronized (TRANSFER_LOCK) {
			pending = pendingGalleryReturn;
			if (pending == null || !pending.token.equals(token)) {
				return false;
			}
		}
		if (pending.target == target) return true;
		if (pending.target != null) pending.target.clearPendingPictureInPictureRestore(token);
		pending.target = target;
		if (target.preparePendingPictureInPictureRestore(token)) {
			VideoDiagnostics.recordUi("pip_return target_bound elapsed_ms="
					+ (SystemClock.elapsedRealtime() - pending.started));
			return true;
		}
		cancelPendingGalleryReturn(token, "target_unavailable");
		return false;
	}

	static void releasePendingGalleryTarget(VideoUnit target, String token, boolean changingConfigurations) {
		synchronized (TRANSFER_LOCK) {
			PendingGalleryReturn pending = pendingGalleryReturn;
			if (pending == null || !pending.token.equals(token) || pending.target != target) return;
			target.clearPendingPictureInPictureRestore(token);
			pending.target = null;
			if (!changingConfigurations) pending.handoff.stop("target_background");
			VideoDiagnostics.recordUi("pip_return target_released changing=" + changingConfigurations);
		}
	}

	static void cancelPendingGalleryReturn(String token, String reason) {
		PendingGalleryReturn pending;
		synchronized (TRANSFER_LOCK) {
			pending = pendingGalleryReturn;
			if (pending == null || !pending.token.equals(token)) return;
			pendingGalleryReturn = null;
		}
		VideoDiagnostics.recordUi("pip_return cancelled reason=" + reason + " elapsed_ms="
				+ (SystemClock.elapsedRealtime() - pending.started));
		if ("target_expired".equals(reason) && pending.target != null) {
			pending.target.showPendingPictureInPictureRestoreError(token);
		}
		pending.destroyPlayer();
	}

	static boolean completePendingGalleryPlayer(VideoUnit target, String token) {
		PendingGalleryReturn pending;
		synchronized (TRANSFER_LOCK) {
			pending = pendingGalleryReturn;
			if (pending == null || !pending.token.equals(token) || pending.target != target) return false;
			pendingGalleryReturn = null;
		}
		VideoDiagnostics.recordUi("pip_return target_ready elapsed_ms="
				+ (SystemClock.elapsedRealtime() - pending.started));
		if (target.adoptPictureInPicturePlayer(pending.player, pending.sourceFile, pending.player.getPosition(),
				pending.playbackSpeed, pending.muted, pending.playing, pending.downloadSession, pending.previewFrame,
				pending.handoff)) {
			pending.finishPreparation();
			VideoDiagnostics.recordUi("pip_return attached elapsed_ms="
					+ (SystemClock.elapsedRealtime() - pending.started));
			return true;
		}
		pending.destroyPlayer();
		return false;
	}

	/** Publish first, retire the previous request second, then schedule the unchanged waits. */
	static void publishGalleryReturn(PendingGalleryReturn pending) {
		PendingGalleryReturn oldPending;
		synchronized (TRANSFER_LOCK) {
			oldPending = pendingGalleryReturn;
			pendingGalleryReturn = pending;
		}
		if (oldPending != null) {
			oldPending.destroyPlayer();
		}
		pending.handler.postDelayed(pending.timeout, RETURN_PAUSE_DELAY_MS);
		pending.handler.postDelayed(pending.expiry, RETURN_EXPIRY_DELAY_MS);
	}

	/** An activity-launch failure retires this request without removing a later replacement. */
	static void discardGalleryReturn(PendingGalleryReturn pending) {
		synchronized (TRANSFER_LOCK) {
			if (pendingGalleryReturn == pending) {
				pendingGalleryReturn = null;
			}
		}
		pending.destroyPlayer();
	}
}
