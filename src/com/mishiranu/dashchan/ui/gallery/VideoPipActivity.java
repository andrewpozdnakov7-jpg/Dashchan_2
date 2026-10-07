package com.mishiranu.dashchan.ui.gallery;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.media.VideoPlayer;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.util.AudioFocus;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.CircularProgressBar;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class VideoPipActivity extends Activity implements VideoPlayer.Listener {
	private static final String TAG = "VideoPipActivity";
	private static final String EXTRA_FILE_PATH = "filePath";
	private static final String EXTRA_POSITION = "position";
	private static final String EXTRA_PLAYBACK_SPEED = "playbackSpeed";
	private static final String EXTRA_MUTED = "muted";
	private static final String EXTRA_PLAYING = "playing";
	private static final String ACTION_TOGGLE_PLAYBACK = VideoPipActivity.class.getName() + ".TOGGLE_PLAYBACK";
	private static final String ACTION_SEEK_BACKWARD = VideoPipActivity.class.getName() + ".SEEK_BACKWARD";
	private static final String ACTION_SEEK_FORWARD = VideoPipActivity.class.getName() + ".SEEK_FORWARD";
	private static final int REQUEST_TOGGLE_PLAYBACK = 1;
	private static final int REQUEST_SEEK_BACKWARD = 2;
	private static final int REQUEST_SEEK_FORWARD = 3;
	private static final long PICTURE_IN_PICTURE_DISMISS_GRACE_PERIOD = 2000L;
	private static final long PICTURE_IN_PICTURE_RETURN_INITIAL_DELAY = 250L;
	private static final long PICTURE_IN_PICTURE_RETURN_RETRY_DELAY = 100L;
	private static final int PICTURE_IN_PICTURE_RETURN_MAX_ATTEMPTS = 20;
	private static final long PICTURE_IN_PICTURE_BOUNDS_OBSERVATION_DELAY = 1000L;

	private static final Object TRANSFER_LOCK = new Object();
	// The PiP activity runs in the same process, so it can reuse the initialized native player.
	private static PendingTransfer pendingTransfer;
	private static PendingGalleryReturn pendingGalleryReturn;
	private static WeakReference<VideoPipActivity> activePictureInPicture = new WeakReference<>(null);

	// Called on the UI thread after the gallery has handed off its new player.
	// Replacing content inside the existing pinned task preserves the system's
	// user-resized bounds. Starting another activity would create a fresh PiP task.
	static boolean reusePictureInPicture(Intent intent) {
		VideoPipActivity activity = activePictureInPicture.get();
		if (activity == null || activity.isFinishing() || activity.isDestroyed()
				|| !activity.isInPictureInPictureMode() || activity.exitedPictureInPicture
				|| activity.returnedToGallery) return false;
		String path = intent.getStringExtra(EXTRA_FILE_PATH);
		PendingTransfer transfer = path != null ? takePendingTransfer(path) : null;
		if (transfer == null) return false;
		activity.replacePictureInPictureContent(intent, transfer);
		return true;
	}

	public static class GalleryRestoreData {
		final String chanName;
		final ArrayList<GalleryItem> galleryItems;
		final ArrayList<GalleryItem> allGalleryItems;
		final Bundle dialogState;
		final android.os.Parcelable gridState;
		final String filter;
		final String sort;
		final int imageIndex;
		final String threadTitle;
		final GalleryOverlay.NavigatePostMode navigatePostMode;

		GalleryRestoreData(String chanName, ArrayList<GalleryItem> galleryItems, int imageIndex,
				String threadTitle, GalleryOverlay.NavigatePostMode navigatePostMode,
				ArrayList<GalleryItem> allGalleryItems, Bundle dialogState, android.os.Parcelable gridState,
				String filter, String sort) {
			this.chanName = chanName;
			this.galleryItems = galleryItems;
			this.imageIndex = imageIndex;
			this.threadTitle = threadTitle;
			this.navigatePostMode = navigatePostMode;
			this.allGalleryItems = allGalleryItems;
			this.dialogState = dialogState;
			this.gridState = gridState;
			this.filter = filter;
			this.sort = sort;
		}
	}

	private static class PendingTransfer {
		public final VideoUnit.PictureInPictureSource source;
		public final VideoPlayer player;
		public final String filePath;
		public final Bitmap previewFrame;
		public final GalleryRestoreData galleryRestoreData;
		public final VideoDownloadSession downloadSession;

		private PendingTransfer(VideoUnit.PictureInPictureSource source, VideoPlayer player, String filePath, Bitmap previewFrame,
				GalleryRestoreData galleryRestoreData, VideoDownloadSession downloadSession) {
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

	private static class PendingGalleryReturn {
		final String token = UUID.randomUUID().toString();
		final long started = SystemClock.elapsedRealtime();
		final WeakReference<VideoPipActivity> owner;
		final Handler handler = new Handler(Looper.getMainLooper());
		final Runnable timeout = this::pauseForLateTarget;
		final Runnable expiry = () -> cancelPendingGalleryReturn(token, "target_expired");
		VideoUnit target;
		boolean disposed;
		final GalleryRestoreData data;
		final VideoPlayer player;
		final File sourceFile;
		final VideoDownloadSession downloadSession;
		final long position;
		final int playbackSpeed;
		final boolean muted;
		final boolean playing;
		final Bitmap previewFrame;
		final VideoPlaybackHandoff handoff;

		PendingGalleryReturn(VideoPipActivity owner, GalleryRestoreData data, VideoPlayer player,
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

	static Intent createIntent(Context context, File file, long position, int playbackSpeed,
			boolean muted, boolean playing, VideoUnit.PictureInPictureSource source, VideoPlayer player, Bitmap previewFrame,
			GalleryRestoreData galleryRestoreData, VideoDownloadSession downloadSession) {
		synchronized (TRANSFER_LOCK) {
			if (pendingTransfer != null) {
				pendingTransfer.recyclePreviewFrame();
			}
			pendingTransfer = new PendingTransfer(source, player, file.getAbsolutePath(), previewFrame,
					galleryRestoreData, downloadSession);
		}
		return new Intent(context, VideoPipActivity.class)
				.putExtra(EXTRA_FILE_PATH, file.getAbsolutePath())
				.putExtra(EXTRA_POSITION, position)
				.putExtra(EXTRA_PLAYBACK_SPEED, playbackSpeed)
				.putExtra(EXTRA_MUTED, muted)
				.putExtra(EXTRA_PLAYING, playing);
	}

	static void cancelPendingTransfer(VideoUnit.PictureInPictureSource source, VideoPlayer player) {
		synchronized (TRANSFER_LOCK) {
			if (pendingTransfer != null && pendingTransfer.source == source && pendingTransfer.player == player) {
				pendingTransfer.recyclePreviewFrame();
				pendingTransfer = null;
			}
		}
	}

	private static PendingTransfer takePendingTransfer(String filePath) {
		synchronized (TRANSFER_LOCK) {
			if (pendingTransfer != null && pendingTransfer.filePath.equals(filePath)) {
				PendingTransfer transfer = pendingTransfer;
				pendingTransfer = null;
				return transfer;
			}
		}
		return null;
	}

	public static String getPendingGalleryReturnToken() {
		synchronized (TRANSFER_LOCK) {
			return pendingGalleryReturn != null ? pendingGalleryReturn.token : null;
		}
	}

	public static Bitmap getPendingGalleryReturnPreview(String token) {
		synchronized (TRANSFER_LOCK) {
			PendingGalleryReturn pending = pendingGalleryReturn;
			Bitmap frame = pending != null && pending.token.equals(token) ? pending.previewFrame : null;
			return frame != null && !frame.isRecycled() ? frame : null;
		}
	}

	public static GalleryOverlay createPendingGalleryReturnOverlay() {
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

	private FrameLayout rootView;
	private CircularProgressBar bufferingProgress;
	private View pipVideoView;
	private final Rect lastSourceRectHint = new Rect();
	private Rational lastPublishedAspectRatio;
	private boolean lastPublishedSeamlessResizeEnabled = true;
	private boolean pictureInPictureActionsPublished;
	private boolean lastPublishedPlaying;
	private int lastPublishedSeekSeconds;
	private boolean pictureInPictureGeometryDeferred;
	private ViewTreeObserver pictureInPictureGeometryObserver;
	private boolean pictureInPictureGeometryWaitReported;
	private String pictureInPictureGeometryWaitReason = "unmeasured";
	private int pictureInPictureParamsPublished;
	private int pictureInPictureParamsSkipped;
	private PipDiagnosticMode pictureInPictureDiagnosticMode = PipDiagnosticMode.NORMAL;
	private int pictureInPictureDiagnosticUpdates;
	private boolean pictureInPictureDiagnosticTrialValid = true;
	private final PipBoundsMonitor pictureInPictureBoundsMonitor = new PipBoundsMonitor();
	private int pictureInPictureBoundsAnomalies;
	private boolean pictureInPictureControlsDeferred;
	private long activityCreatedElapsedMs;
	private long entryRequestedElapsedMs = -1L;
	private boolean firstVideoFrameReceived;
	private ImageView previewView;
	private Bitmap previewFrame;
	private GalleryRestoreData galleryRestoreData;
	private VideoUnit.PictureInPictureSource source;

	private VideoUnit getSource() {
		return source != null ? source.get() : null;
	}

	private void clearSource() {
		if (source != null) {
			source.active = false;
			source.attach(null);
			source = null;
		}
	}
	private VideoPlayer player;
	private VideoDownloadSession downloadSession;
	private AudioFocus audioFocus;
	private int playbackSpeed;
	private boolean muted;
	private boolean startPlaying;
	private boolean finishedPlayback;
	private boolean enteredPictureInPicture;
	private boolean exitedPictureInPicture;
	private boolean stoppedWhileInPictureInPicture;
	private boolean returnedToGallery;
	private boolean receiverRegistered;
	private boolean resumedAfterPictureInPictureExit;
	private boolean resumePlaybackAfterPictureInPictureExit;
	private boolean pictureInPictureEntryScheduled;
	private boolean previewFrameHideScheduled;
	private boolean holdPreviewForGalleryReturn;
	private boolean galleryRestorePrepared;
	private boolean returnToGalleryScheduled;
	private boolean standalonePlayback;
	private boolean activityStarted;
	private boolean activityResumed;
	private boolean pictureInPictureEntryRequested;
	private boolean pictureInPictureFirstDraw;
	private int transitionSequence;
	private int returnToGalleryAttempts;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable enterPictureInPictureAfterDraw = this::enterPictureInPicture;
	private final ViewTreeObserver.OnPreDrawListener pictureInPictureGeometryPreDraw =
			this::publishPictureInPictureGeometryBeforeDraw;
	private final Runnable observeSettledPictureInPictureBounds = this::observePictureInPictureBounds;

	private void recordTransition(String event) {
		VideoUnit source = getSource();
		VideoDiagnostics.recordUi("pip_transition activity=" + Integer.toHexString(System.identityHashCode(this))
				+ " task=" + getTaskId() + " sequence=" + transitionSequence + " event=" + event
				+ " resumed=" + activityResumed + " focus=" + hasWindowFocus()
				+ " gallery_focus=" + (source != null && source.hasPictureInPictureGalleryFocus())
				+ " entry_requested=" + pictureInPictureEntryRequested
				+ " in_pip=" + isInPictureInPictureMode() + " entered=" + enteredPictureInPicture
				+ " exited=" + exitedPictureInPicture + " prepared=" + galleryRestorePrepared
				+ " scheduled=" + returnToGalleryScheduled + " returned=" + returnedToGallery);
	}

	private void cancelGalleryReturn(String reason) {
		handler.removeCallbacks(returnToGalleryAfterExit);
		returnToGalleryScheduled = false;
		VideoUnit source = getSource();
		if (galleryRestorePrepared && source != null) source.cancelPictureInPictureGalleryPreparation();
		galleryRestorePrepared = false;
		returnToGalleryAttempts = 0;
		transitionSequence++;
		recordTransition("cancel_" + reason);
	}
	private final Runnable recordSettledPipGeometry = () -> recordPipGeometry("settled");

	private void recordPipGeometry(String event) {
		if (!VideoDiagnostics.isExtendedRecording() || rootView == null) return;
		try {
			recordPipGeometrySnapshot(event);
		} catch (RuntimeException e) {
			VideoDiagnostics.recordUi("pip_geometry failed=" + e.getClass().getSimpleName());
		}
	}

	private void recordPipGeometrySnapshot(String event) {
		Configuration config = getResources().getConfiguration();
		Window window = getWindow();
		WindowInsets insets = rootView.getRootWindowInsets();
		VideoDiagnostics.recordUi("pip_geometry schema=1 event=" + event
				+ " in_pip=" + isInPictureInPictureMode() + " orientation=" + config.orientation
				+ " screen_dp=" + config.screenWidthDp + "x" + config.screenHeightDp
				+ " density_dpi=" + config.densityDpi
				+ " window_bounds=" + getWindowManager().getCurrentWindowMetrics().getBounds().toShortString()
				+ " window_size=" + window.getAttributes().width + "x" + window.getAttributes().height
				+ " window_gravity=" + window.getAttributes().gravity + " flags=" + window.getAttributes().flags
				+ " params_published=" + pictureInPictureParamsPublished
				+ " params_skipped=" + pictureInPictureParamsSkipped
				+ " geometry_deferred=" + pictureInPictureGeometryDeferred
				+ " bounds_anomalies=" + pictureInPictureBoundsAnomalies
				+ " controls_deferred=" + pictureInPictureControlsDeferred
				+ " system_insets=" + (insets != null ? insets.getInsets(WindowInsets.Type.systemBars()) : null)
				+ " cutout_insets=" + (insets != null ? insets.getInsets(WindowInsets.Type.displayCutout()) : null));
		VideoDiagnostics.recordViewGeometry("pip_" + event, rootView);
		for (int i = 0; i < rootView.getChildCount(); i++) {
			View child = rootView.getChildAt(i);
			VideoDiagnostics.recordViewGeometry("pip_child_" + event, child);
		}
	}

	private void schedulePipGeometry() {
		if (VideoDiagnostics.isExtendedRecording()) {
			handler.removeCallbacks(recordSettledPipGeometry);
			handler.postDelayed(recordSettledPipGeometry, 350L);
		}
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		// A resize/rotation callback can precede the corresponding view layout. Do not
		// publish that intermediate layout back into the system's PiP animation.
		pictureInPictureGeometryDeferred = true;
		recordPipGeometry("configuration_before");
		super.onConfigurationChanged(newConfig);
		recordPipGeometry("configuration_after");
		recordPictureInPictureProbe("configuration");
		schedulePictureInPictureGeometryUpdate();
		schedulePictureInPictureBoundsCheck();
		schedulePipGeometry();
	}

	private final Runnable hidePreviewFrameRunnable = () -> {
		previewFrameHideScheduled = false;
		ImageView previewView = this.previewView;
		this.previewView = null;
		this.previewFrame = null;
		if (previewView != null) {
			VideoDiagnostics.recordUi("pip preview_hidden first_frame=" + firstVideoFrameReceived);
			previewView.setVisibility(ImageView.INVISIBLE);
			previewView.setImageDrawable(null);
		}
	};
	private final Runnable finishDismissedPictureInPicture = () -> {
		if (enteredPictureInPicture && exitedPictureInPicture && !returnedToGallery
				&& !resumedAfterPictureInPictureExit && !isInPictureInPictureMode()
				&& !isFinishing() && !hasWindowFocus()) {
			VideoDiagnostics.recordUi("pip dismissal_confirmed");
			VideoPlayer player = this.player;
			if (player != null) {
				player.setPlaying(false);
			}
			if (audioFocus != null) {
				audioFocus.release();
			}
			finishAndRemoveTask();
		}
	};
	private final Runnable returnToGalleryAfterExit = () -> {
		returnToGalleryScheduled = false;
		if (!canContinueReturnToGallery()) {
			cancelGalleryReturn("not_foreground");
			return;
		}
		VideoUnit source = getSource();
		VideoPlayer player = this.player;
		if (player == null) {
			finish();
			return;
		}
		if (galleryRestoreData != null && !galleryRestoreData.galleryItems.isEmpty()) {
			// A surviving source holder is not necessarily the final foreground window:
			// bringing its activity forward can recreate it immediately. Use the same
			// measured-window handoff as after a rotation, without attaching to that
			// intermediate holder first.
			restoreGalleryFromSnapshot(source != null ? "prepared_live_gallery" : "prepared_snapshot");
			return;
		}
		if (source == null) {
			// The host may be between destroying its old views and attaching the new ones.
			// A removed gallery cannot reattach; only wait for a temporary view recreation.
			if (this.source != null && this.source.active && !this.source.galleryClosed) {
				retryReturnToGallery();
			} else {
				restoreGalleryFromSnapshot(this.source != null && this.source.galleryClosed
						? "source_closed" : "source_missing");
			}
			return;
		}
		if (!galleryRestorePrepared) {
			if (!source.preparePictureInPicturePlayerRestore(player)) {
				restoreGalleryFromSnapshot("source_"
						+ source.getPictureInPictureRestoreState(player).name());
				return;
			}
			galleryRestorePrepared = true;
			scheduleReturnToGallery(PICTURE_IN_PICTURE_RETURN_RETRY_DELAY);
			return;
		}
		VideoUnit.PictureInPictureRestoreState state = source.getPictureInPictureRestoreState(player);
		if (state == VideoUnit.PictureInPictureRestoreState.READY) {
			returnToGallery();
		} else if (state == VideoUnit.PictureInPictureRestoreState.HOLDER_UNAVAILABLE) {
			retryReturnToGallery();
		} else {
			restoreGalleryFromSnapshot("source_" + state.name());
		}
	};

	private void scheduleDismissedPictureInPictureCheck() {
		handler.removeCallbacks(finishDismissedPictureInPicture);
		handler.postDelayed(finishDismissedPictureInPicture,
				PICTURE_IN_PICTURE_DISMISS_GRACE_PERIOD);
	}
	private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (ACTION_TOGGLE_PLAYBACK.equals(intent.getAction())) {
				togglePlayback();
			} else if (ACTION_SEEK_BACKWARD.equals(intent.getAction())) {
				seekBy(-1);
			} else if (ACTION_SEEK_FORWARD.equals(intent.getAction())) {
				seekBy(1);
			} else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
				handleScreenOff();
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		activityCreatedElapsedMs = SystemClock.elapsedRealtime();
		// Snapshot once per Activity: changing a preference cannot mix modes mid-trial.
		pictureInPictureDiagnosticMode = PipDiagnosticMode.fromPreference(Preferences.getPipDiagnosticMode());
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
				&& !pictureInPictureDiagnosticMode.isSeamlessResizeEnabled()) pictureInPictureDiagnosticTrialValid = false;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			// Register both transitions before entry/finish; onDestroy is too late to configure closing.
			overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
			overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
		} else {
			disableLegacyActivityTransition();
		}
		// Leave splash ownership with the system. A custom exit listener transfers the
		// splash to this activity and can trigger system-server cleanup of a detached
		// PiP task when the app process is killed from Recents.
		VideoDiagnostics.recordUi("pip splash_handling=system");
		if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
			finish();
			return;
		}
		Intent intent = getIntent();
		String filePath = intent.getStringExtra(EXTRA_FILE_PATH);
		PendingTransfer transfer = filePath != null ? takePendingTransfer(filePath) : null;
		if (transfer == null) {
			finish();
			return;
		}
		source = transfer.source;
		player = transfer.player;
		downloadSession = transfer.downloadSession;
		previewFrame = transfer.previewFrame;
		galleryRestoreData = transfer.galleryRestoreData;
		playbackSpeed = intent.getIntExtra(EXTRA_PLAYBACK_SPEED, 1000);
		muted = intent.getBooleanExtra(EXTRA_MUTED, false);
		startPlaying = intent.getBooleanExtra(EXTRA_PLAYING, true);

		Window window = getWindow();
		ViewUtils.setWindowLayoutFullscreen(window);
		WindowManager.LayoutParams attributes = window.getAttributes();
		attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
		window.setAttributes(attributes);
		rootView = new FrameLayout(this);
		rootView.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
			if (l != oldL || t != oldT || r != oldR || b != oldB) {
				schedulePictureInPictureGeometryUpdate();
				schedulePipGeometry();
				schedulePictureInPictureBoundsCheck();
			}
		});
		rootView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) {
				schedulePictureInPictureGeometryUpdate();
			}
			@Override public void onViewDetachedFromWindow(View view) {
				cancelPictureInPictureGeometryUpdate();
			}
		});
		rootView.setBackgroundColor(Color.BLACK);
		rootView.setOnClickListener(v -> {
			if (standalonePlayback) {
				togglePlayback();
			}
		});
		setContentView(rootView);
		WindowInsetsController insetsController = window.getInsetsController();
		if (insetsController != null) {
			insetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
			insetsController.hide(WindowInsets.Type.systemBars());
		}

		IntentFilter controlFilter = new IntentFilter(ACTION_TOGGLE_PLAYBACK);
		controlFilter.addAction(ACTION_SEEK_BACKWARD);
		controlFilter.addAction(ACTION_SEEK_FORWARD);
		controlFilter.addAction(Intent.ACTION_SCREEN_OFF);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(controlReceiver, controlFilter, Context.RECEIVER_NOT_EXPORTED);
		} else {
			registerReceiver(controlReceiver, controlFilter);
		}
		receiverRegistered = true;
		audioFocus = AudioFocus.forVideo(this, change -> {
			VideoPlayer player = this.player;
			if (player == null) {
				return;
			}
			switch (change) {
				case LOSS: {
					startPlaying = false;
					player.setPlaying(false);
					updatePictureInPictureControls();
					break;
				}
				case LOSS_TRANSIENT: {
					// Repeated transient loss must not erase the intent to resume.
					startPlaying |= player.isPlaying();
					player.setPlaying(false);
					updatePictureInPictureControls();
					break;
				}
				case GAIN: {
					if (startPlaying) {
						player.setPlaying(true);
					}
					updatePictureInPictureControls();
					break;
				}
			}
		});

		player.setListener(this);
		attachPlayerView();
		VideoDiagnostics.recordUi("pip activity_created preview=" + (previewFrame != null));
		activePictureInPicture = new WeakReference<>(this);
		recordPipGeometry("created");
		schedulePictureInPictureAfterFirstDraw();
	}

	private void attachPlayerView() {
		player.releaseVideoView();
		player.setVideoViewFrameCallback(this::scheduleHidePreviewFrame);
		pipVideoView = player.getVideoView(this);
		pipVideoView.addOnLayoutChangeListener((view, left, top, right, bottom,
				oldLeft, oldTop, oldRight, oldBottom) -> {
			if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
				// PiP menu resizing must not republish controls or PiP-window bounds.
				schedulePictureInPictureGeometryUpdate();
			}
		});
		rootView.addView(pipVideoView, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
		if (previewView == null) {
			previewView = new ImageView(this);
			previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
			rootView.addView(previewView, new FrameLayout.LayoutParams(
					FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		}
		previewView.bringToFront();
		previewView.setImageBitmap(previewFrame);
		previewView.setVisibility(previewFrame != null ? View.VISIBLE : View.INVISIBLE);
		if (bufferingProgress == null) {
			bufferingProgress = new CircularProgressBar(this);
			bufferingProgress.setIndeterminate(true);
			rootView.addView(bufferingProgress, new FrameLayout.LayoutParams(
					FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
		}
		bufferingProgress.setVisible(false, true);
		bufferingProgress.bringToFront();
		player.setPlaybackSpeed(playbackSpeed);
		player.setMuted(muted);
		String filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
		if (filePath != null) VideoPositionMemory.restoreOnce(player, new File(filePath));
		if (startPlaying && !muted && player.isAudioPresent() && !audioFocus.acquire()) {
			startPlaying = false;
		}
		player.setPlaying(startPlaying);
		rootView.setKeepScreenOn(startPlaying);
		if (downloadSession != null) {
			downloadSession.setListener(downloadListener);
			if (downloadSession.getError() != null) {
				downloadListener.onDownloadError(downloadSession, downloadSession.getError());
				return;
			}
		}
	}

	private void replacePictureInPictureContent(Intent intent, PendingTransfer transfer) {
		endPictureInPictureDiagnosticTrial("content_replaced");
		cancelPictureInPictureGeometryUpdate();
		cancelPictureInPictureBoundsObservation();
		cancelGalleryReturn("replace_content");
		handler.removeCallbacks(finishDismissedPictureInPicture);
		handler.removeCallbacks(hidePreviewFrameRunnable);
		VideoPlayer previous = player;
		VideoUnit previousSource = getSource();
		VideoPositionMemory.save(previous, finishedPlayback);
		if (downloadSession != null) {
			downloadSession.setListener(null);
			downloadSession.cancel();
		}
		if (previous != null) {
			previous.setListener(null);
			previous.setVideoViewFrameCallback(null);
			if (previousSource == null || !previousSource.closePictureInPicturePlayer(previous)) {
				previous.releaseVideoViewAndDestroyAsync();
			}
		}
		clearSource();
		audioFocus.release();
		setIntent(intent);
		source = transfer.source;
		if (source != null) source.enteredPictureInPicture = isInPictureInPictureMode();
		player = transfer.player;
		downloadSession = transfer.downloadSession;
		galleryRestoreData = transfer.galleryRestoreData;
		previewFrame = transfer.previewFrame;
		playbackSpeed = intent.getIntExtra(EXTRA_PLAYBACK_SPEED, 1000);
		muted = intent.getBooleanExtra(EXTRA_MUTED, false);
		startPlaying = intent.getBooleanExtra(EXTRA_PLAYING, true);
		finishedPlayback = false;
		firstVideoFrameReceived = false;
		previewFrameHideScheduled = false;
		holdPreviewForGalleryReturn = false;
		player.setListener(this);
		attachPlayerView();
		updatePictureInPictureControls();
		schedulePictureInPictureGeometryUpdate();
		recordTransition("content_replaced_same_window");
		recordPipGeometry("content_replaced");
	}

	private void schedulePictureInPictureAfterFirstDraw() {
		if (pictureInPictureEntryScheduled) {
			return;
		}
		pictureInPictureEntryScheduled = true;
		rootView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
			@Override
			public boolean onPreDraw() {
				if (rootView.getViewTreeObserver().isAlive()) {
					rootView.getViewTreeObserver().removeOnPreDrawListener(this);
				}
				pictureInPictureFirstDraw = true;
				handler.post(enterPictureInPictureAfterDraw);
				return true;
			}
		});
	}

	private void scheduleHidePreviewFrame() {
		if (!firstVideoFrameReceived) {
			firstVideoFrameReceived = true;
			long now = SystemClock.elapsedRealtime();
			VideoDiagnostics.recordUi("pip first_video_frame activity_age_ms=" + (now - activityCreatedElapsedMs)
					+ " entry_age_ms=" + (entryRequestedElapsedMs >= 0L ? now - entryRequestedElapsedMs : -1L));
		}
		if (previewView != null && !previewFrameHideScheduled && !holdPreviewForGalleryReturn) {
			previewFrameHideScheduled = true;
			// TextureView reports a new frame while the hierarchy may still be building its display list.
			// Keep the overlay child attached for this activity's lifetime: some Android builds cannot safely
			// remove a FrameLayout child around the PiP transition even from a later animation callback.
			handler.post(hidePreviewFrameRunnable);
		}
	}

	private void hidePreviewFrameImmediately() {
		handler.removeCallbacks(hidePreviewFrameRunnable);
		hidePreviewFrameRunnable.run();
	}

	private void holdReturnPreview() {
		if (player == null || previewView == null) return;
		Bitmap frame = player.getCurrentFrame();
		if (frame == null) return;
		handler.removeCallbacks(hidePreviewFrameRunnable);
		holdPreviewForGalleryReturn = true;
		previewFrame = frame;
		previewView.setImageBitmap(frame);
		previewView.setVisibility(View.VISIBLE);
		VideoDiagnostics.recordUi("pip return_preview_held");
	}

	private void enterPictureInPicture() {
		VideoPlayer player = this.player;
		if (player == null || isFinishing() || isDestroyed() || !activityResumed
				|| !pictureInPictureFirstDraw || pictureInPictureEntryRequested
				|| isInPictureInPictureMode() || returnedToGallery) {
			return;
		}
		cancelGalleryReturn("enter");
		if (enteredPictureInPicture) endPictureInPictureDiagnosticTrial("same_activity_reentry");
		pictureInPictureEntryRequested = true;
		recordTransition("entry_request");
		Rational aspectRatio = getPictureInPictureAspectRatio(player.getDimensions());
		Rect sourceRect = pictureInPictureDiagnosticMode.allowsHintAtEntry() ? getPictureInPictureSourceRect() : null;
		if (pictureInPictureDiagnosticMode.isDiagnostic() && pictureInPictureDiagnosticMode.allowsHintAtEntry()
				&& sourceRect == null) pictureInPictureDiagnosticTrialValid = false;
		PictureInPictureParams params = createPictureInPictureParams(aspectRatio, sourceRect);
		try {
			// enterPictureInPictureMode publishes this snapshot itself; do not send it twice.
			// Retain the exact builder inputs: Params getters require Android 13.
			lastPublishedAspectRatio = aspectRatio;
			lastPublishedSeamlessResizeEnabled = pictureInPictureDiagnosticMode.isSeamlessResizeEnabled();
			pictureInPictureActionsPublished = true;
			lastPublishedPlaying = player.isPlaying();
			lastPublishedSeekSeconds = Preferences.getVideoDoubleTapSeekInterval();
			if (sourceRect != null && !sourceRect.isEmpty()) {
				lastSourceRectHint.set(sourceRect);
				VideoDiagnostics.recordUi("pip source_rect=" + sourceRect.toShortString() + " in_pip=false");
			}
			recordPictureInPictureParams("entry");
			recordPictureInPictureProbe("entry");
			entryRequestedElapsedMs = SystemClock.elapsedRealtime();
			VideoDiagnostics.recordUi("pip entry_requested source_rect="
					+ (lastSourceRectHint.isEmpty() ? "none" : lastSourceRectHint.toShortString())
					+ " first_frame=" + firstVideoFrameReceived + " preview=" + (previewView != null));
			if (!enterPictureInPictureMode(params)) {
				VideoDiagnostics.recordUi("pip entry_rejected");
				finish();
			}
		} catch (IllegalArgumentException | IllegalStateException e) {
			VideoDiagnostics.recordUi("pip entry_failed=" + e.getClass().getSimpleName());
			finish();
		}
	}

	private PictureInPictureParams createPictureInPictureParams(Rational aspectRatio, Rect sourceRect) {
		PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
		// Crop to the actual video, not the full portrait activity including its black bars.
		// Missing source bounds lets Android cover the PiP transition with a content overlay.
		if (sourceRect != null) {
			builder.setSourceRectHint(sourceRect);
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			builder.setSeamlessResizeEnabled(pictureInPictureDiagnosticMode.isSeamlessResizeEnabled());
		}
		if (aspectRatio != null) builder.setAspectRatio(aspectRatio);
		VideoPlayer player = this.player;
		builder.setActions(createPictureInPictureActions(player != null && player.isPlaying(),
				Preferences.getVideoDoubleTapSeekInterval()));
		return builder.build();
	}

	private static Rational getPictureInPictureAspectRatio(Point dimensions) {
		if (dimensions != null && dimensions.x > 0 && dimensions.y > 0) {
			int width = dimensions.x;
			int height = dimensions.y;
			if ((long) width * 1000L > (long) height * 2390L) {
				width = 2390;
				height = 1000;
			} else if ((long) height * 1000L > (long) width * 2390L) {
				width = 1000;
				height = 2390;
			}
			return new Rational(width, height);
		}
		return null;
	}

	private List<RemoteAction> createPictureInPictureActions(boolean playing, int seekSeconds) {
		int iconResource = playing ? R.drawable.ic_pause : R.drawable.ic_play_arrow;
		String title = getString(playing ? R.string.pause : R.string.play);
		RemoteAction seekBackward = createRemoteAction(ACTION_SEEK_BACKWARD, REQUEST_SEEK_BACKWARD,
				R.drawable.ic_fast_rewind, getString(R.string.video_seek_backward__format, seekSeconds));
		RemoteAction toggle = createRemoteAction(ACTION_TOGGLE_PLAYBACK, REQUEST_TOGGLE_PLAYBACK,
				iconResource, title);
		RemoteAction seekForward = createRemoteAction(ACTION_SEEK_FORWARD, REQUEST_SEEK_FORWARD,
				R.drawable.ic_fast_forward, getString(R.string.video_seek_forward__format, seekSeconds));
		return Arrays.asList(seekBackward, toggle, seekForward);
	}

	private RemoteAction createRemoteAction(String action, int requestCode, int iconResource, String title) {
		Intent controlIntent = new Intent(action).setPackage(getPackageName());
		PendingIntent pendingIntent = PendingIntent.getBroadcast(this, requestCode, controlIntent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		return new RemoteAction(Icon.createWithResource(this, iconResource), title, title, pendingIntent);
	}

	private boolean ownsVideoView() {
		return player != null && pipVideoView != null && pipVideoView.getParent() == rootView
				&& player.isVideoView(pipVideoView);
	}

	private Rect getPictureInPictureSourceRect() {
		pictureInPictureGeometryWaitReason = "view_not_ready";
		if (!ownsVideoView() || !rootView.isAttachedToWindow() || !rootView.isShown()
				|| rootView.isLayoutRequested() || pipVideoView.isLayoutRequested()) return null;
		// The app owns WINDOW-local video bounds; Android owns screen/PiP position.
		// Re-measure every publication; never reuse an already-offset system hint.
		if (rootView.getDisplay() == null) return null;
		int rotation = rootView.getDisplay().getRotation();
		Rect windowBounds = getWindowManager().getCurrentWindowMetrics().getBounds();
		Rect displayBounds = getWindowManager().getMaximumWindowMetrics().getBounds();
		View decor = getWindow().getDecorView();
		if (!PipGeometryGate.matchesLayout(windowBounds.width(), windowBounds.height(),
				decor.getWidth(), decor.getHeight())) {
			pictureInPictureGeometryWaitReason = "layout_mismatch window=" + windowBounds.width() + "x"
					+ windowBounds.height() + " decor=" + decor.getWidth() + "x" + decor.getHeight();
			return null;
		}
		// A stashed window still intersects the display. A stale, completely off-screen
		// pre-rotation window is not a valid animation source and must not be echoed back.
		if (isInPictureInPictureMode() && !PipGeometryGate.intersectsDisplay(
				windowBounds.left, windowBounds.top, windowBounds.right, windowBounds.bottom,
				displayBounds.left, displayBounds.top, displayBounds.right, displayBounds.bottom)) {
			pictureInPictureGeometryWaitReason = "offscreen window=" + windowBounds.toShortString()
					+ " display=" + displayBounds.toShortString();
			return null;
		}
		Rect sourceRect = new Rect();
		if (!pipVideoView.getLocalVisibleRect(sourceRect) || sourceRect.isEmpty()) return null;
		int[] location = new int[2];
		pipVideoView.getLocationInWindow(location);
		sourceRect.offset(location[0], location[1]);
		if (rootView.getDisplay() == null || rotation != rootView.getDisplay().getRotation()) return null;
		return sourceRect.intersect(0, 0, decor.getWidth(), decor.getHeight()) ? sourceRect : null;
	}

	private void updatePictureInPictureControls() {
		VideoPlayer player = this.player;
		if (player != null && ownsVideoView() && !isFinishing() && !isDestroyed()) {
			boolean playing = player.isPlaying();
			rootView.setKeepScreenOn(playing);
			if (pictureInPictureDiagnosticMode.isDiagnostic()) {
				// Actions still invoke the live player; only their visual snapshot is frozen.
				pictureInPictureControlsDeferred = false;
				return;
			}
			int seekSeconds = Preferences.getVideoDoubleTapSeekInterval();
			if (pictureInPictureActionsPublished && playing == lastPublishedPlaying
					&& seekSeconds == lastPublishedSeekSeconds) {
				pictureInPictureControlsDeferred = false;
				pictureInPictureParamsSkipped++;
				return;
			}
			pictureInPictureControlsDeferred = true;
			// Coalesce actions and layout into one publication from the same frame.
			// Never race an actions-only update against a pending rotation layout.
			schedulePictureInPictureGeometryUpdate();
		}
	}

	private void schedulePictureInPictureGeometryUpdate() {
		if (!pictureInPictureDiagnosticMode.allowsParameterUpdate(isInPictureInPictureMode())) {
			cancelPictureInPictureGeometryUpdate();
			return;
		}
		if (!activityStarted || player == null || !ownsVideoView() || isFinishing() || isDestroyed()) return;
		pictureInPictureGeometryDeferred = true;
		// Ensure a frame even when a previous request is waiting for a valid layout.
		rootView.invalidate();
		if (pictureInPictureGeometryObserver != null && pictureInPictureGeometryObserver.isAlive()) return;
		pictureInPictureGeometryObserver = rootView.getViewTreeObserver();
		if (pictureInPictureGeometryObserver.isAlive()) {
			pictureInPictureGeometryObserver.addOnPreDrawListener(pictureInPictureGeometryPreDraw);
		}
	}

	private boolean publishPictureInPictureGeometryBeforeDraw() {
		if (!pictureInPictureDiagnosticMode.allowsParameterUpdate(isInPictureInPictureMode())) {
			cancelPictureInPictureGeometryUpdate();
			return true;
		}
		if (!activityStarted || player == null || !ownsVideoView() || isFinishing() || isDestroyed()) {
			cancelPictureInPictureGeometryUpdate();
			return true;
		}
		// Android's exit destination is laid out before the exit animation. Publish
		// from that traversal, not from a delayed callback after the animation started.
		// Entry already publishes a full snapshot; wait for its mode callback instead.
		if (pictureInPictureEntryRequested || (!enteredPictureInPicture && !standalonePlayback)) return true;
		if (updatePictureInPictureGeometry()) {
			cancelPictureInPictureGeometryUpdate();
		} else if (!pictureInPictureGeometryWaitReported) {
			pictureInPictureGeometryWaitReported = true;
			VideoDiagnostics.recordUi("pip_params schema=3 deferred=" + pictureInPictureGeometryWaitReason
					+ " in_pip=" + isInPictureInPictureMode());
		}
		// Do not cancel drawing or continuously invalidate an unready window. The
		// next actual layout/frame retries the queued update without polling timers.
		return true;
	}

	private void cancelPictureInPictureGeometryUpdate() {
		ViewTreeObserver observer = pictureInPictureGeometryObserver;
		pictureInPictureGeometryObserver = null;
		if (observer != null) {
			if (!observer.isAlive() && rootView != null) observer = rootView.getViewTreeObserver();
			if (observer.isAlive()) observer.removeOnPreDrawListener(pictureInPictureGeometryPreDraw);
		}
		pictureInPictureGeometryDeferred = false;
		pictureInPictureGeometryWaitReported = false;
	}

	private boolean updatePictureInPictureGeometry() {
		if (pictureInPictureDiagnosticMode == PipDiagnosticMode.LAYOUT_HINT) return updateDiagnosticPictureInPictureGeometry();
		Rational aspectRatio = getPictureInPictureAspectRatio(player.getDimensions());
		boolean aspectChanged = aspectRatio != null && !aspectRatio.equals(lastPublishedAspectRatio);
		Rect sourceRect = getPictureInPictureSourceRect();
		if (sourceRect == null) return false;
		boolean sourceChanged = !sourceRect.equals(lastSourceRectHint);
		boolean playing = player.isPlaying();
		int seekSeconds = Preferences.getVideoDoubleTapSeekInterval();
		boolean controlsChanged = !pictureInPictureActionsPublished || playing != lastPublishedPlaying
				|| seekSeconds != lastPublishedSeekSeconds;
		if (!aspectChanged && !sourceChanged && !controlsChanged) {
			pictureInPictureControlsDeferred = false;
			pictureInPictureParamsSkipped++;
			return true;
		}
		PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
		if (aspectChanged) builder.setAspectRatio(aspectRatio);
		if (controlsChanged) builder.setActions(createPictureInPictureActions(playing, seekSeconds));
		if (publishPictureInPictureParams(builder, sourceRect, controlsChanged ? "layout_and_controls" : "layout")) {
			if (aspectChanged) lastPublishedAspectRatio = aspectRatio;
			pictureInPictureActionsPublished = true;
			pictureInPictureControlsDeferred = false;
			lastPublishedPlaying = playing;
			lastPublishedSeekSeconds = seekSeconds;
			if (aspectChanged) schedulePictureInPictureBoundsCheck();
		}
		// Runtime rejection is recorded by the publisher. Retry on the next real
		// event, not on every frame of an animation the system has already rejected.
		return true;
	}

	private boolean updateDiagnosticPictureInPictureGeometry() {
		Rect sourceRect = getPictureInPictureSourceRect();
		if (sourceRect == null) return false;
		if (sourceRect.equals(lastSourceRectHint)) return true;
		// Mode 2 changes only the hint. No actions/aspect-ratio IPC can confound the comparison.
		publishPictureInPictureParams(new PictureInPictureParams.Builder(), sourceRect, "diagnostic_layout");
		return true;
	}

	private void schedulePictureInPictureBoundsCheck() {
		handler.removeCallbacks(observeSettledPictureInPictureBounds);
		if (!canObservePictureInPictureBounds() || rootView.getDisplay() == null) return;
		long now = SystemClock.elapsedRealtime();
		pictureInPictureBoundsMonitor.observeRotation(rootView.getDisplay().getRotation(), now);
		if (pictureInPictureBoundsMonitor.isWatching(now)) {
			handler.postDelayed(observeSettledPictureInPictureBounds, PICTURE_IN_PICTURE_BOUNDS_OBSERVATION_DELAY);
		}
	}

	private boolean canObservePictureInPictureBounds() {
		return activityStarted && enteredPictureInPicture && !exitedPictureInPicture && !returnedToGallery
				&& isInPictureInPictureMode() && !isFinishing() && !isDestroyed()
				&& rootView != null && rootView.isAttachedToWindow() && ownsVideoView();
	}

	private void observePictureInPictureBounds() {
		if (!canObservePictureInPictureBounds()) return;
		try {
			if (rootView.getDisplay() == null) return;
			long now = SystemClock.elapsedRealtime();
			if (pictureInPictureBoundsMonitor.observeRotation(rootView.getDisplay().getRotation(), now)) {
				schedulePictureInPictureBoundsCheck();
				return;
			}
			Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
			Rect displayBounds = getWindowManager().getMaximumWindowMetrics().getBounds();
			boolean stale = pictureInPictureBoundsMonitor.shouldReport(now, bounds.left, bounds.top, bounds.right,
					bounds.bottom, displayBounds.left, displayBounds.top, displayBounds.right, displayBounds.bottom);
			if (stale) {
				pictureInPictureBoundsMonitor.markReported();
				pictureInPictureBoundsAnomalies++;
			}
			// Observation only: no relocation, ratio pulse, playback reset or PiP re-entry.
			recordPictureInPictureBoundsObservation("settled visible=" + Rect.intersects(bounds, displayBounds)
					+ " stale_rotation_bounds=" + stale + " bounds=" + bounds.toShortString()
					+ " display=" + displayBounds.toShortString() + " anomalies=" + pictureInPictureBoundsAnomalies);
		} catch (RuntimeException e) {
			recordPictureInPictureBoundsObservation("failed=" + e.getClass().getSimpleName());
		}
	}
	private void cancelPictureInPictureBoundsObservation() {
		handler.removeCallbacks(observeSettledPictureInPictureBounds);
		pictureInPictureBoundsMonitor.reset();
	}

	private void recordPictureInPictureBoundsObservation(String message) {
		// Keep rotation events in logcat too: verbose geometry can fill the UI ring.
		String event = "pip_rotation_bounds schema=2 " + message;
		VideoDiagnostics.recordUi(event);
		Log.i(TAG, event);
	}

	private boolean publishPictureInPictureParams(PictureInPictureParams.Builder builder, Rect sourceRect,
			String reason) {
		// Last-line guard covers any future caller, not just the current layout scheduler.
		if (!pictureInPictureDiagnosticMode.allowsParameterUpdate(isInPictureInPictureMode())) {
			pictureInPictureParamsSkipped++;
			return false;
		}
		// Always include the fresh window-local hint from this pre-draw snapshot:
		// null/empty values do not clear Android's retained, screen-offset hint.
		builder.setSourceRectHint(sourceRect);
		boolean seamlessResizeEnabled = pictureInPictureDiagnosticMode.isSeamlessResizeEnabled();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				&& seamlessResizeEnabled != lastPublishedSeamlessResizeEnabled) {
			// Restore the normal flag on the next publication if mode 3's trial ended.
			// Hint-only mode 2 retains its entry flag without sending another field.
			builder.setSeamlessResizeEnabled(seamlessResizeEnabled);
		}
		try {
			setPictureInPictureParams(builder.build());
			lastPublishedSeamlessResizeEnabled = seamlessResizeEnabled;
			if (enteredPictureInPicture || pictureInPictureEntryRequested) pictureInPictureDiagnosticUpdates++;
			lastSourceRectHint.set(sourceRect);
			VideoDiagnostics.recordUi("pip source_rect schema=3 local=" + sourceRect.toShortString()
					+ " in_pip=" + isInPictureInPictureMode());
			recordPictureInPictureParams(reason);
			recordPictureInPictureProbe("params_update");
			return true;
		} catch (IllegalArgumentException | IllegalStateException e) {
			VideoDiagnostics.recordUi("pip_params failed=" + e.getClass().getSimpleName() + " reason=" + reason);
			return false;
		}
	}

	private void recordPictureInPictureParams(String reason) {
		pictureInPictureParamsPublished++;
		VideoDiagnostics.recordUi("pip_params schema=4 reason=" + reason + " in_pip="
				+ isInPictureInPictureMode() + " published=" + pictureInPictureParamsPublished
				+ " skipped=" + pictureInPictureParamsSkipped + " probe_mode=" + pictureInPictureDiagnosticMode.probeId
				+ " probe_updates=" + pictureInPictureDiagnosticUpdates
				+ " seamless_resize_requested=" + getPictureInPictureSeamlessResizeDiagnosticValue());
	}

	private String getPictureInPictureSeamlessResizeDiagnosticValue() {
		// Report the requested policy, not an unobservable OEM animation outcome.
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				? Boolean.toString(pictureInPictureDiagnosticMode.isSeamlessResizeEnabled()) : "unsupported";
	}

	private void endPictureInPictureDiagnosticTrial(String reason) {
		if (!pictureInPictureDiagnosticMode.isDiagnostic()) return;
		pictureInPictureDiagnosticTrialValid = false;
		recordPictureInPictureProbe("trial_ended_" + reason);
		pictureInPictureDiagnosticMode = PipDiagnosticMode.NORMAL;
		ClickableToast.show(R.string.pip_diagnostic_trial_ended);
	}

	private void recordPictureInPictureProbe(String event) {
		if (!pictureInPictureDiagnosticMode.isDiagnostic() && !VideoDiagnostics.isExtendedRecording()) return;
		try {
			Rect windowBounds = getWindowManager().getCurrentWindowMetrics().getBounds();
			Rect displayBounds = getWindowManager().getMaximumWindowMetrics().getBounds();
			int rotation = rootView != null && rootView.getDisplay() != null ? rootView.getDisplay().getRotation() : -1;
			String record = "pip_probe schema=2 event=" + event + " mode=" + pictureInPictureDiagnosticMode.probeId
					+ " updates=" + pictureInPictureDiagnosticUpdates + " valid=" + pictureInPictureDiagnosticTrialValid
					+ " seamless_resize_requested=" + getPictureInPictureSeamlessResizeDiagnosticValue()
					+ " elapsed_ms=" + (SystemClock.elapsedRealtime() - activityCreatedElapsedMs)
					+ " in_pip=" + isInPictureInPictureMode() + " rotation=" + rotation
					+ " window=" + windowBounds.toShortString() + " display=" + displayBounds.toShortString()
					+ " hint=" + lastSourceRectHint.toShortString() + " intersects=" + Rect.intersects(windowBounds, displayBounds);
			VideoDiagnostics.recordUi(record);
			Log.i("PipRotationProbe", record);
		} catch (RuntimeException e) {
			VideoDiagnostics.recordUi("pip_probe geometry_failed=" + e.getClass().getSimpleName());
		}
	}

	private void togglePlayback() {
		VideoPlayer player = this.player;
		if (player == null) {
			return;
		}
		boolean playing = !player.isPlaying();
		if (playing && player.isAudioPresent() && !muted && !audioFocus.acquire()) {
			return;
		}
		if (!playing) {
			audioFocus.release();
		}
		if (playing && finishedPlayback) {
			// Resuming at EOF cannot produce frames: explicit Play starts a new pass.
			finishedPlayback = false;
			VideoDiagnostics.recordUi("pip replay_after_complete position=" + player.getPosition());
			player.setPosition(0L);
		}
		startPlaying = playing;
		player.setPlaying(playing);
		VideoPositionMemory.save(player, finishedPlayback);
		updatePictureInPictureControls();
		recordPictureInPictureProbe("playback_control");
	}

	private void seekBy(int direction) {
		VideoPlayer player = this.player;
		if (player == null) {
			return;
		}
		long position = player.getPosition()
				+ direction * Preferences.getVideoDoubleTapSeekInterval() * 1000L;
		long duration = player.getDuration();
		if (duration > 0L) {
			position = Math.min(position, duration);
		}
		position = Math.max(position, 0L);
		// A manual seek away from EOF must resume at the chosen position, not at zero.
		finishedPlayback = duration > 0L && position >= duration;
		VideoDiagnostics.recordUi("pip seek position=" + position + " at_end=" + finishedPlayback);
		player.setPosition(position);
		VideoPositionMemory.save(player, finishedPlayback);
		recordPictureInPictureProbe("seek_control");
	}

	private void handleScreenOff() {
		VideoPlayer player = this.player;
		if (player != null && player.isPlaying() && Preferences.getVideoScreenOffAction()
				== Preferences.VideoScreenOffAction.PAUSE) {
			startPlaying = false;
			player.setPlaying(false);
			if (audioFocus != null) {
				audioFocus.release();
			}
			updatePictureInPictureControls();
		}
	}

	private boolean canReturnToGallery() {
		return exitedPictureInPicture && enteredPictureInPicture && !isInPictureInPictureMode()
				&& activityResumed && hasWindowFocus() && !returnedToGallery
				&& !standalonePlayback && !isFinishing() && !isDestroyed() && !isChangingConfigurations();
	}

	private boolean canContinueReturnToGallery() {
		VideoUnit source = getSource();
		// Preparing the gallery may move focus to its dialog. Only that actual focus,
		// not the preparation flag alone, permits completing the handoff in this case.
		boolean foreground = activityResumed && hasWindowFocus()
				|| galleryRestorePrepared && source != null && source.hasPictureInPictureGalleryFocus();
		return exitedPictureInPicture && enteredPictureInPicture && !isInPictureInPictureMode()
				&& foreground && !returnedToGallery && !standalonePlayback
				&& !isFinishing() && !isDestroyed() && !isChangingConfigurations();
	}

	private void maybeReturnToGallery() {
		if (canReturnToGallery()) {
			scheduleReturnToGallery(PICTURE_IN_PICTURE_RETURN_INITIAL_DELAY);
		}
	}

	private void scheduleReturnToGallery(long delayMillis) {
		if (!returnToGalleryScheduled && canContinueReturnToGallery()) {
			returnToGalleryScheduled = true;
			recordTransition("return_scheduled");
			handler.postDelayed(returnToGalleryAfterExit, delayMillis);
		}
	}

	private void retryReturnToGallery() {
		returnToGalleryAttempts++;
		if (returnToGalleryAttempts < PICTURE_IN_PICTURE_RETURN_MAX_ATTEMPTS) {
			VideoDiagnostics.recordUi("pip return_to_gallery_wait attempt=" + returnToGalleryAttempts);
			scheduleReturnToGallery(PICTURE_IN_PICTURE_RETURN_RETRY_DELAY);
		} else {
			restoreGalleryFromSnapshot("gallery_timeout");
		}
	}

	private void returnToGallery() {
		if (!canContinueReturnToGallery()) {
			cancelGalleryReturn("handoff_not_foreground");
			return;
		}
		VideoPlayer player = this.player;
		VideoUnit source = getSource();
		if (player == null || source == null || returnedToGallery) {
			return;
		}
		boolean playing = exitedPictureInPicture
				? resumePlaybackAfterPictureInPictureExit : player.isPlaying();
		long position = player.getPosition();
		player.setVideoViewFrameCallback(null);
		if (!source.restorePictureInPicturePlayer(player, position, playbackSpeed, muted, playing,
				downloadSession, audioFocus)) {
			VideoUnit.PictureInPictureRestoreState state = source.getPictureInPictureRestoreState(player);
			if (state == VideoUnit.PictureInPictureRestoreState.HOLDER_UNAVAILABLE) {
				retryReturnToGallery();
			} else {
				restoreGalleryFromSnapshot("restore_" + state.name());
			}
			return;
		}
		returnedToGallery = true;
		downloadSession = null; // Ownership returned with the player.
		handler.removeCallbacks(returnToGalleryAfterExit);
		returnToGalleryScheduled = false;
		VideoDiagnostics.recordUi("pip return_to_gallery attempts=" + returnToGalleryAttempts);
		// Keep the preview visible until this window actually stops/destroys.
		// Its TextureView has already moved to the gallery by this point.
		audioFocus.release();
		clearSource();
		this.player = null;
		// Do not launch the host again when its gallery already owns the foreground.
		if (!source.hasPictureInPictureGalleryFocus()) source.bringGalleryToForeground(this);
		finish();
	}

	private void restoreGalleryFromSnapshot(String reason) {
		if (!canReturnToGallery()) {
			cancelGalleryReturn("snapshot_not_foreground");
			return;
		}
		VideoPlayer player = this.player;
		GalleryRestoreData data = galleryRestoreData;
		String filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
		if (player == null || returnedToGallery) {
			return;
		}
		if (data == null || data.galleryItems.isEmpty() || filePath == null) {
			continueStandalonePlayback("snapshot_missing_" + reason);
			return;
		}
		handler.removeCallbacks(returnToGalleryAfterExit);
		handler.removeCallbacks(finishDismissedPictureInPicture);
		returnToGalleryScheduled = false;
		boolean playing = exitedPictureInPicture
				? resumePlaybackAfterPictureInPictureExit : player.isPlaying();
		long position = player.getPosition();
		VideoPlaybackHandoff handoff = new VideoPlaybackHandoff(this, player, audioFocus, "pip_return");
		player.setVideoViewFrameCallback(null);
		Bitmap returnPreview = player.getCurrentFrame();
		// Retain the live output while Android prepares the new gallery window. Its view
		// is detached by the destination only after that window has a measured holder.
		player.prepareVideoViewForTransfer();
		player.setListener(null);
		if (downloadSession != null) downloadSession.setListener(null);
		PendingGalleryReturn pending = new PendingGalleryReturn(this, data, player, new File(filePath), position,
				playbackSpeed, muted, playing, downloadSession, returnPreview, handoff);
		downloadSession = null; // The pending return now owns the download, even if the old gallery is destroyed.
		PendingGalleryReturn oldPending;
		synchronized (TRANSFER_LOCK) {
			oldPending = pendingGalleryReturn;
			pendingGalleryReturn = pending;
		}
		if (oldPending != null) {
			oldPending.destroyPlayer();
		}
		pending.handler.postDelayed(pending.timeout, 1500L);
		pending.handler.postDelayed(pending.expiry, 30000L);
		VideoUnit source = getSource();
		if (source != null) {
			source.detachPictureInPicturePlayer(player);
		}
		returnedToGallery = true;
		clearSource();
		this.player = null;
		audioFocus.release();
		VideoDiagnostics.recordUi("pip recreate_gallery reason=" + reason);
		Intent intent = new Intent(this, MainActivity.class)
				.setAction(C.ACTION_RETURN_FROM_PICTURE_IN_PICTURE)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
						| Intent.FLAG_ACTIVITY_SINGLE_TOP);
		try {
			startActivity(intent);
			// MainActivity's singleTask return may itself retire this activity. Do not
			// additionally tear down its window before the destination is ready.
		} catch (RuntimeException e) {
			synchronized (TRANSFER_LOCK) {
				if (pendingGalleryReturn == pending) {
					pendingGalleryReturn = null;
				}
			}
			pending.destroyPlayer();
			finish();
		}
	}

	private void continueStandalonePlayback(String reason) {
		VideoPlayer player = this.player;
		if (player == null || returnedToGallery || standalonePlayback) {
			return;
		}
		handler.removeCallbacks(returnToGalleryAfterExit);
		handler.removeCallbacks(finishDismissedPictureInPicture);
		returnToGalleryScheduled = false;
		galleryRestorePrepared = false;
		standalonePlayback = true;
		holdPreviewForGalleryReturn = false;
		hidePreviewFrameImmediately();
		VideoDiagnostics.recordUi("pip standalone_playback reason=" + reason
				+ " attempts=" + returnToGalleryAttempts);
		Log.w(TAG, "Continuing standalone playback after PiP exit: " + reason);
		VideoUnit source = getSource();
		clearSource();
		if (source != null) {
			source.detachPictureInPicturePlayer(player);
		}
		startPlaying = resumePlaybackAfterPictureInPictureExit;
		if (startPlaying && !muted && player.isAudioPresent() && !audioFocus.acquire()) {
			startPlaying = false;
		}
		player.setPlaying(startPlaying);
		rootView.setKeepScreenOn(startPlaying);
		updatePictureInPictureControls();
	}

	private void suspendPlaybackAfterPictureInPictureExit() {
		VideoPlayer player = this.player;
		resumePlaybackAfterPictureInPictureExit |= player != null && player.isPlaying();
		if (player != null) {
			player.setPlaying(false);
		}
		if (audioFocus != null) {
			audioFocus.release();
		}
		if (rootView != null) {
			rootView.setKeepScreenOn(false);
		}
		VideoDiagnostics.recordUi("pip exit_playback_suspended resume="
				+ resumePlaybackAfterPictureInPictureExit);
	}

	@Override
	protected void onStart() {
		super.onStart();
		activityStarted = true;
		schedulePictureInPictureGeometryUpdate();
		schedulePictureInPictureBoundsCheck();
		if (isInPictureInPictureMode()) {
			stoppedWhileInPictureInPicture = false;
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		activityResumed = true;
		schedulePictureInPictureGeometryUpdate();
		recordTransition("resume");
		if (!enteredPictureInPicture && pictureInPictureFirstDraw) {
			handler.removeCallbacks(enterPictureInPictureAfterDraw);
			handler.post(enterPictureInPictureAfterDraw);
		}
		if (enteredPictureInPicture && exitedPictureInPicture && !isInPictureInPictureMode()) {
			resumedAfterPictureInPictureExit = true;
		}
		VideoDiagnostics.recordUi("pip on_resume in_pip=" + isInPictureInPictureMode()
				+ " exited=" + exitedPictureInPicture);
		handler.removeCallbacks(finishDismissedPictureInPicture);
		maybeReturnToGallery();
	}

	@Override
	protected void onPause() {
		VideoPositionMemory.save(player, finishedPlayback);
		activityResumed = false;
		handler.removeCallbacks(enterPictureInPictureAfterDraw);
		// A prepared gallery may be receiving focus; the queued handoff must verify it.
		if (!galleryRestorePrepared) cancelGalleryReturn("pause");
		recordTransition("pause");
		super.onPause();
	}

	@Override
	protected void onUserLeaveHint() {
		super.onUserLeaveHint();
		cancelGalleryReturn("user_leave");
		if (standalonePlayback && player != null && !isInPictureInPictureMode()) {
			enterPictureInPicture();
		}
	}

	@Override
	protected void onStop() {
		VideoPositionMemory.save(player, finishedPlayback);
		activityStarted = false;
		super.onStop();
		cancelPictureInPictureBoundsObservation();
		cancelPictureInPictureGeometryUpdate();
		handler.removeCallbacks(enterPictureInPictureAfterDraw);
		VideoUnit source = getSource();
		if (!galleryRestorePrepared || source == null || !source.hasPictureInPictureGalleryFocus()) {
			cancelGalleryReturn("stop");
		}
		recordTransition("stop");
		if (enteredPictureInPicture && isInPictureInPictureMode()) {
			stoppedWhileInPictureInPicture = true;
		}
		VideoDiagnostics.recordUi("pip on_stop in_pip=" + isInPictureInPictureMode()
				+ " exited=" + exitedPictureInPicture + " resumed_after_exit="
				+ resumedAfterPictureInPictureExit);
		if (enteredPictureInPicture && exitedPictureInPicture && !isInPictureInPictureMode()
				&& !resumedAfterPictureInPictureExit && !returnedToGallery
				&& !isChangingConfigurations() && !isFinishing()) {
			suspendPlaybackAfterPictureInPictureExit();
			scheduleDismissedPictureInPictureCheck();
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		recordTransition(hasFocus ? "focus_gained" : "focus_lost");
		if (!hasFocus && !galleryRestorePrepared) cancelGalleryReturn("focus_lost");
		if (hasFocus) {
			if (enteredPictureInPicture && exitedPictureInPicture && !isInPictureInPictureMode()) {
				resumedAfterPictureInPictureExit = true;
			}
			handler.removeCallbacks(finishDismissedPictureInPicture);
			maybeReturnToGallery();
		}
	}

	@Override
	public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
		cancelPictureInPictureBoundsObservation();
		pictureInPictureEntryRequested = false;
		pictureInPictureGeometryDeferred = true;
		schedulePictureInPictureGeometryUpdate();
		transitionSequence++;
		recordTransition(isInPictureInPictureMode ? "mode_enter" : "mode_exit");
		recordPipGeometry("mode_changed");
		recordPictureInPictureProbe(isInPictureInPictureMode ? "mode_enter" : "mode_exit");
		schedulePipGeometry();
		if (isInPictureInPictureMode) {
			holdPreviewForGalleryReturn = false;
			if (firstVideoFrameReceived) hidePreviewFrameImmediately();
			handler.removeCallbacks(finishDismissedPictureInPicture);
			handler.removeCallbacks(returnToGalleryAfterExit);
			enteredPictureInPicture = true;
			if (source != null) source.enteredPictureInPicture = true;
			exitedPictureInPicture = false;
			stoppedWhileInPictureInPicture = false;
			resumedAfterPictureInPictureExit = false;
			resumePlaybackAfterPictureInPictureExit = false;
			galleryRestorePrepared = false;
			returnToGalleryScheduled = false;
			standalonePlayback = false;
			returnToGalleryAttempts = 0;
			VideoDiagnostics.recordUi("pip mode_changed=true entry_age_ms=" + (entryRequestedElapsedMs >= 0L
					? SystemClock.elapsedRealtime() - entryRequestedElapsedMs : -1L));
			schedulePictureInPictureBoundsCheck();
		} else if (enteredPictureInPicture) {
			holdReturnPreview();
			exitedPictureInPicture = true;
			resumedAfterPictureInPictureExit = false;
			VideoDiagnostics.recordUi("pip mode_changed=false");
			resumePlaybackAfterPictureInPictureExit = player != null && player.isPlaying();
			if (stoppedWhileInPictureInPicture) {
				suspendPlaybackAfterPictureInPictureExit();
			} else {
				VideoDiagnostics.recordUi("pip exit_playback_continues resume="
						+ resumePlaybackAfterPictureInPictureExit);
			}
			scheduleDismissedPictureInPictureCheck();
			handler.post(this::maybeReturnToGallery);
		}
	}

	@Override
	protected void onDestroy() {
		cancelPictureInPictureBoundsObservation();
		if (activePictureInPicture.get() == this) activePictureInPicture.clear();
		activityResumed = false;
		handler.removeCallbacks(enterPictureInPictureAfterDraw);
		recordTransition("destroy");
		handler.removeCallbacks(recordSettledPipGeometry);
		cancelPictureInPictureGeometryUpdate();
		handler.removeCallbacks(finishDismissedPictureInPicture);
		handler.removeCallbacks(returnToGalleryAfterExit);
		if (receiverRegistered) {
			unregisterReceiver(controlReceiver);
			receiverRegistered = false;
		}
		if (audioFocus != null) {
			audioFocus.release();
		}
		VideoPlayer player = this.player;
		if (player != null) {
			player.setVideoViewFrameCallback(null);
			boolean playing = player.isPlaying();
			long position = player.getPosition();
			VideoPositionMemory.save(player, finishedPlayback);
			player.setPlaying(false);
			player.releaseVideoView();
			player.setListener(null);
			VideoUnit source = getSource();
			boolean handled;
			if (!enteredPictureInPicture) {
				handled = source != null && source.restorePictureInPicturePlayer(player, position,
						playbackSpeed, muted, playing, downloadSession);
				if (handled) downloadSession = null;
			} else {
				handled = source != null && source.closePictureInPicturePlayer(player);
			}
			if (!handled) {
				player.destroyAsync();
			}
			this.player = null;
			clearSource();
		}
		if (downloadSession != null) {
			downloadSession.cancel();
			downloadSession = null;
		}
		hidePreviewFrameImmediately();
		VideoDiagnostics.recordUi("pip activity_destroyed entered=" + enteredPictureInPicture
				+ " exited=" + exitedPictureInPicture + " returned=" + returnedToGallery);
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			disableLegacyActivityTransition();
		}
		super.onDestroy();
	}

	@SuppressWarnings("deprecation")
	private void disableLegacyActivityTransition() {
		// Compatibility only: overrideActivityTransition is unavailable on Android 11-13.
		overridePendingTransition(0, 0);
	}

	@Override
	public void onComplete(VideoPlayer player) {
		runOnUiThread(() -> {
			if (this.player == player) {
				boolean loop = Preferences.getVideoCompletionMode() == Preferences.VideoCompletionMode.LOOP;
				VideoPositionMemory.save(player, true);
				finishedPlayback = !loop;
				VideoDiagnostics.recordUi("pip complete loop=" + loop + " position=" + player.getPosition()
						+ " duration=" + player.getDuration());
				if (loop) {
					startPlaying = true;
					player.setPosition(0L);
					player.setPlaying(true);
				} else {
					startPlaying = false;
					player.setPlaying(false);
					audioFocus.release();
				}
				updatePictureInPictureControls();
			}
		});
	}

	@Override
	public void onBusyStateChange(VideoPlayer player, boolean busy) {
		if (this.player == player) {
			if (bufferingProgress != null) {
				bufferingProgress.setVisible(busy, true);
				if (!busy) bufferingProgress.cancelVisibilityTransient();
			}
			VideoDiagnostics.recordUi("pip buffering_or_seeking=" + busy + " download_complete="
					+ (downloadSession == null || downloadSession.isComplete()));
		}
	}

	private final VideoDownloadSession.Listener downloadListener = new VideoDownloadSession.Listener() {
		@Override
		public void onInitialized(VideoDownloadSession session) {}

		@Override
		public void onDownloadChanged(VideoDownloadSession session) {
			if (downloadSession == session && session.isComplete()) {
				VideoDiagnostics.recordUi("pip download_complete bytes=" + session.getTotal());
			}
		}

		@Override
		public void onDownloadError(VideoDownloadSession session, ErrorItem error) {
			if (downloadSession != session || isFinishing()) return;
			VideoDiagnostics.recordUi("pip download_failed type=" + error.type);
			ClickableToast.show(error.toString());
			finish();
		}
	};

	@Override
	public void onDurationChange(VideoPlayer player, long duration) {
		runOnUiThread(() -> {
			if (this.player != player) return;
			String filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
			if (filePath != null) VideoPositionMemory.restoreOnce(player, new File(filePath));
		});
	}

	@Override
	public void onDimensionChange(VideoPlayer player) {
		runOnUiThread(() -> {
			if (this.player == player) {
				updatePictureInPictureControls();
				schedulePictureInPictureGeometryUpdate();
			}
		});
	}
}
