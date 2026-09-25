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
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
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
		final int imageIndex;
		final String threadTitle;
		final GalleryOverlay.NavigatePostMode navigatePostMode;

		GalleryRestoreData(String chanName, ArrayList<GalleryItem> galleryItems, int imageIndex,
				String threadTitle, GalleryOverlay.NavigatePostMode navigatePostMode) {
			this.chanName = chanName;
			this.galleryItems = galleryItems;
			this.imageIndex = imageIndex;
			this.threadTitle = threadTitle;
			this.navigatePostMode = navigatePostMode;
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
		final GalleryRestoreData data;
		final VideoPlayer player;
		final File sourceFile;
		final VideoDownloadSession downloadSession;
		final long position;
		final int playbackSpeed;
		final boolean muted;
		final boolean playing;
		boolean overlayCreated;
		final Bitmap previewFrame;

		PendingGalleryReturn(GalleryRestoreData data, VideoPlayer player, File sourceFile, long position,
				int playbackSpeed, boolean muted, boolean playing, VideoDownloadSession downloadSession,
				Bitmap previewFrame) {
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

		void destroyPlayer() {
			if (downloadSession != null) downloadSession.cancel();
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

	public static GalleryOverlay createPendingGalleryReturnOverlay() {
		synchronized (TRANSFER_LOCK) {
			PendingGalleryReturn pending = pendingGalleryReturn;
			if (pending == null || pending.overlayCreated) {
				return null;
			}
			pending.overlayCreated = true;
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
			pendingGalleryReturn = null;
		}
		if (target.adoptPictureInPicturePlayer(pending.player, pending.sourceFile, pending.position,
				pending.playbackSpeed, pending.muted, pending.playing, pending.downloadSession, pending.previewFrame)) {
			return true;
		}
		pending.destroyPlayer();
		return false;
	}

	private FrameLayout rootView;
	private View pipVideoView;
	private final Rect lastSourceRectHint = new Rect();
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
	private boolean activityResumed;
	private boolean pictureInPictureEntryRequested;
	private boolean pictureInPictureFirstDraw;
	private int transitionSequence;
	private int returnToGalleryAttempts;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable enterPictureInPictureAfterDraw = this::enterPictureInPicture;

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
		recordPipGeometry("configuration_before");
		super.onConfigurationChanged(newConfig);
		recordPipGeometry("configuration_after");
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
			if (l != oldL || t != oldT || r != oldR || b != oldB) schedulePipGeometry();
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
		audioFocus = new AudioFocus(this, change -> {
			VideoPlayer player = this.player;
			if (player == null) {
				return;
			}
			switch (change) {
				case LOSS:
				case LOSS_TRANSIENT: {
					startPlaying = player.isPlaying();
					player.setPlaying(false);
					updatePictureInPictureParams();
					break;
				}
				case GAIN: {
					if (startPlaying) {
						player.setPlaying(true);
					}
					updatePictureInPictureParams();
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
				// Publish the laid-out video bounds before the system animates entry or exit.
				updatePictureInPictureParams();
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
		player.setPlaybackSpeed(playbackSpeed);
		player.setMuted(muted);
		if (startPlaying && !muted && player.isAudioPresent()) {
			audioFocus.acquire();
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
		cancelGalleryReturn("replace_content");
		handler.removeCallbacks(finishDismissedPictureInPicture);
		handler.removeCallbacks(hidePreviewFrameRunnable);
		VideoPlayer previous = player;
		VideoUnit previousSource = getSource();
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
		updatePictureInPictureParams();
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
		pictureInPictureEntryRequested = true;
		recordTransition("entry_request");
		PictureInPictureParams params = createPictureInPictureParams(player.getDimensions());
		try {
			setPictureInPictureParams(params);
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

	private PictureInPictureParams createPictureInPictureParams(Point dimensions) {
		PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
		// Crop to the actual video, not the full portrait activity including its black bars.
		// Missing source bounds lets Android cover the PiP transition with a content overlay.
		Rect sourceRect = new Rect();
		if (ownsVideoView() && pipVideoView.getGlobalVisibleRect(sourceRect) && !sourceRect.isEmpty()) {
			builder.setSourceRectHint(sourceRect);
			if (!lastSourceRectHint.equals(sourceRect)) {
				lastSourceRectHint.set(sourceRect);
				VideoDiagnostics.recordUi("pip source_rect=" + sourceRect.toShortString()
						+ " in_pip=" + isInPictureInPictureMode());
			}
		} else {
			lastSourceRectHint.setEmpty();
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			builder.setSeamlessResizeEnabled(true);
		}
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
			builder.setAspectRatio(new Rational(width, height));
		}
		VideoPlayer player = this.player;
		boolean playing = player != null && player.isPlaying();
		int iconResource = playing ? R.drawable.ic_pause : R.drawable.ic_play_arrow;
		String title = getString(playing ? R.string.pause : R.string.play);
		int seekSeconds = Preferences.getVideoDoubleTapSeekInterval();
		RemoteAction seekBackward = createRemoteAction(ACTION_SEEK_BACKWARD, REQUEST_SEEK_BACKWARD,
				R.drawable.ic_fast_rewind, getString(R.string.video_seek_backward__format, seekSeconds));
		RemoteAction toggle = createRemoteAction(ACTION_TOGGLE_PLAYBACK, REQUEST_TOGGLE_PLAYBACK,
				iconResource, title);
		RemoteAction seekForward = createRemoteAction(ACTION_SEEK_FORWARD, REQUEST_SEEK_FORWARD,
				R.drawable.ic_fast_forward, getString(R.string.video_seek_forward__format, seekSeconds));
		builder.setActions(Arrays.asList(seekBackward, toggle, seekForward));
		return builder.build();
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

	private void updatePictureInPictureParams() {
		VideoPlayer player = this.player;
		if (player != null && ownsVideoView() && !isFinishing() && !isDestroyed()) {
			rootView.setKeepScreenOn(player.isPlaying());
			setPictureInPictureParams(createPictureInPictureParams(player.getDimensions()));
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
		updatePictureInPictureParams();
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
			updatePictureInPictureParams();
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
		player.setPlaying(false);
		player.setVideoViewFrameCallback(null);
		if (!source.restorePictureInPicturePlayer(player, position, playbackSpeed, muted, playing,
				downloadSession)) {
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
		player.setPlaying(false);
		player.setVideoViewFrameCallback(null);
		Bitmap returnPreview = player.getCurrentFrame();
		player.releaseVideoView();
		player.setListener(null);
		if (downloadSession != null) downloadSession.setListener(null);
		PendingGalleryReturn pending = new PendingGalleryReturn(data, player, new File(filePath), position,
				playbackSpeed, muted, playing, downloadSession, returnPreview);
		downloadSession = null; // The pending return now owns the download, even if the old gallery is destroyed.
		PendingGalleryReturn oldPending;
		synchronized (TRANSFER_LOCK) {
			oldPending = pendingGalleryReturn;
			pendingGalleryReturn = pending;
		}
		if (oldPending != null) {
			oldPending.destroyPlayer();
		}
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
			finish();
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
		if (startPlaying && !muted && player.isAudioPresent()) {
			audioFocus.acquire();
		}
		player.setPlaying(startPlaying);
		rootView.setKeepScreenOn(startPlaying);
		updatePictureInPictureParams();
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
		if (isInPictureInPictureMode()) {
			stoppedWhileInPictureInPicture = false;
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		activityResumed = true;
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
		super.onStop();
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
		pictureInPictureEntryRequested = false;
		transitionSequence++;
		recordTransition(isInPictureInPictureMode ? "mode_enter" : "mode_exit");
		recordPipGeometry("mode_changed");
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
		if (activePictureInPicture.get() == this) activePictureInPicture.clear();
		activityResumed = false;
		handler.removeCallbacks(enterPictureInPictureAfterDraw);
		recordTransition("destroy");
		handler.removeCallbacks(recordSettledPipGeometry);
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
				updatePictureInPictureParams();
			}
		});
	}

	@Override
	public void onBusyStateChange(VideoPlayer player, boolean busy) {
		if (this.player == player) {
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
	public void onDimensionChange(VideoPlayer player) {
		runOnUiThread(() -> {
			if (this.player == player) {
				updatePictureInPictureParams();
			}
		});
	}
}
