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
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import android.view.Gravity;
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
import com.mishiranu.dashchan.media.VideoPlayer;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.util.AudioFocus;
import com.mishiranu.dashchan.util.ViewUtils;
import java.io.File;
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
		public final VideoUnit source;
		public final VideoPlayer player;
		public final String filePath;
		public final Bitmap previewFrame;
		public final GalleryRestoreData galleryRestoreData;

		private PendingTransfer(VideoUnit source, VideoPlayer player, String filePath, Bitmap previewFrame,
				GalleryRestoreData galleryRestoreData) {
			this.source = source;
			this.player = player;
			this.filePath = filePath;
			this.previewFrame = previewFrame;
			this.galleryRestoreData = galleryRestoreData;
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
		final long position;
		final int playbackSpeed;
		final boolean muted;
		final boolean playing;
		boolean overlayCreated;

		PendingGalleryReturn(GalleryRestoreData data, VideoPlayer player, File sourceFile, long position,
				int playbackSpeed, boolean muted, boolean playing) {
			this.data = data;
			this.player = player;
			this.sourceFile = sourceFile;
			this.position = position;
			this.playbackSpeed = playbackSpeed;
			this.muted = muted;
			this.playing = playing;
		}

		void destroyPlayer() {
			player.setListener(null);
			player.setPlaying(false);
			player.releaseVideoViewAndDestroyAsync();
		}
	}

	static Intent createIntent(Context context, File file, long position, int playbackSpeed,
			boolean muted, boolean playing, VideoUnit source, VideoPlayer player, Bitmap previewFrame,
			GalleryRestoreData galleryRestoreData) {
		synchronized (TRANSFER_LOCK) {
			if (pendingTransfer != null) {
				pendingTransfer.recyclePreviewFrame();
			}
			pendingTransfer = new PendingTransfer(source, player, file.getAbsolutePath(), previewFrame,
					galleryRestoreData);
		}
		return new Intent(context, VideoPipActivity.class)
				.putExtra(EXTRA_FILE_PATH, file.getAbsolutePath())
				.putExtra(EXTRA_POSITION, position)
				.putExtra(EXTRA_PLAYBACK_SPEED, playbackSpeed)
				.putExtra(EXTRA_MUTED, muted)
				.putExtra(EXTRA_PLAYING, playing);
	}

	static void cancelPendingTransfer(VideoUnit source, VideoPlayer player) {
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
				pending.playbackSpeed, pending.muted, pending.playing)) {
			return true;
		}
		pending.destroyPlayer();
		return false;
	}

	private FrameLayout rootView;
	private ImageView previewView;
	private Bitmap previewFrame;
	private GalleryRestoreData galleryRestoreData;
	private VideoUnit source;
	private VideoPlayer player;
	private AudioFocus audioFocus;
	private int playbackSpeed;
	private boolean muted;
	private boolean startPlaying;
	private boolean enteredPictureInPicture;
	private boolean exitedPictureInPicture;
	private boolean stoppedWhileInPictureInPicture;
	private boolean returnedToGallery;
	private boolean receiverRegistered;
	private boolean resumedAfterPictureInPictureExit;
	private boolean resumePlaybackAfterPictureInPictureExit;
	private boolean pictureInPictureEntryScheduled;
	private boolean previewFrameHideScheduled;
	private boolean galleryRestorePrepared;
	private boolean returnToGalleryScheduled;
	private boolean standalonePlayback;
	private int returnToGalleryAttempts;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable hidePreviewFrameRunnable = () -> {
		previewFrameHideScheduled = false;
		ImageView previewView = this.previewView;
		this.previewView = null;
		this.previewFrame = null;
		if (previewView != null) {
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
			return;
		}
		VideoUnit source = this.source;
		VideoPlayer player = this.player;
		if (player == null) {
			finish();
			return;
		}
		if (source == null) {
			restoreGalleryFromSnapshot("source_missing");
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
		overridePendingTransition(0, 0);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			getSplashScreen().setOnExitAnimationListener(splashScreenView -> splashScreenView.remove());
		}
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
		player.releaseVideoView();
		player.setVideoViewFrameCallback(this::scheduleHidePreviewFrame);
		rootView.addView(player.getVideoView(this), new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
		if (previewFrame != null) {
			previewView = new ImageView(this);
			previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
			previewView.setImageBitmap(previewFrame);
			rootView.addView(previewView, new FrameLayout.LayoutParams(
					FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		}
		player.setPlaybackSpeed(playbackSpeed);
		player.setMuted(muted);
		if (startPlaying && !muted && player.isAudioPresent()) {
			audioFocus.acquire();
		}
		player.setPlaying(startPlaying);
		rootView.setKeepScreenOn(startPlaying);
		VideoDiagnostics.recordUi("pip activity_created preview=" + (previewFrame != null));
		schedulePictureInPictureAfterFirstDraw();
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
				rootView.post(VideoPipActivity.this::enterPictureInPicture);
				return true;
			}
		});
	}

	private void scheduleHidePreviewFrame() {
		if (!previewFrameHideScheduled) {
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

	private void enterPictureInPicture() {
		VideoPlayer player = this.player;
		if (player == null || isFinishing()) {
			return;
		}
		PictureInPictureParams params = createPictureInPictureParams(player.getDimensions());
		setPictureInPictureParams(params);
		try {
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

	private void updatePictureInPictureParams() {
		VideoPlayer player = this.player;
		if (player != null) {
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
		player.setPosition(Math.max(position, 0L));
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
				&& hasWindowFocus() && !returnedToGallery && !standalonePlayback && !isFinishing();
	}

	private boolean canContinueReturnToGallery() {
		return exitedPictureInPicture && enteredPictureInPicture && !isInPictureInPictureMode()
				&& (galleryRestorePrepared || hasWindowFocus()) && !returnedToGallery
				&& !standalonePlayback && !isFinishing();
	}

	private void maybeReturnToGallery() {
		if (canReturnToGallery()) {
			scheduleReturnToGallery(PICTURE_IN_PICTURE_RETURN_INITIAL_DELAY);
		}
	}

	private void scheduleReturnToGallery(long delayMillis) {
		if (!returnToGalleryScheduled && canContinueReturnToGallery()) {
			returnToGalleryScheduled = true;
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
		VideoPlayer player = this.player;
		VideoUnit source = this.source;
		if (player == null || source == null || returnedToGallery) {
			return;
		}
		boolean playing = exitedPictureInPicture
				? resumePlaybackAfterPictureInPictureExit : player.isPlaying();
		long position = player.getPosition();
		player.setPlaying(false);
		player.setVideoViewFrameCallback(null);
		if (!source.restorePictureInPicturePlayer(player, position, playbackSpeed, muted, playing)) {
			VideoUnit.PictureInPictureRestoreState state = source.getPictureInPictureRestoreState(player);
			if (state == VideoUnit.PictureInPictureRestoreState.HOLDER_UNAVAILABLE) {
				retryReturnToGallery();
			} else {
				restoreGalleryFromSnapshot("restore_" + state.name());
			}
			return;
		}
		returnedToGallery = true;
		handler.removeCallbacks(returnToGalleryAfterExit);
		returnToGalleryScheduled = false;
		VideoDiagnostics.recordUi("pip return_to_gallery attempts=" + returnToGalleryAttempts);
		hidePreviewFrameImmediately();
		audioFocus.release();
		this.source = null;
		this.player = null;
		source.bringGalleryToForeground(this);
		finish();
	}

	private void restoreGalleryFromSnapshot(String reason) {
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
		player.releaseVideoView();
		player.setListener(null);
		PendingGalleryReturn pending = new PendingGalleryReturn(data, player, new File(filePath), position,
				playbackSpeed, muted, playing);
		PendingGalleryReturn oldPending;
		synchronized (TRANSFER_LOCK) {
			oldPending = pendingGalleryReturn;
			pendingGalleryReturn = pending;
		}
		if (oldPending != null) {
			oldPending.destroyPlayer();
		}
		VideoUnit source = this.source;
		if (source != null) {
			source.detachPictureInPicturePlayer(player);
		}
		returnedToGallery = true;
		this.source = null;
		this.player = null;
		audioFocus.release();
		hidePreviewFrameImmediately();
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
		VideoDiagnostics.recordUi("pip standalone_playback reason=" + reason
				+ " attempts=" + returnToGalleryAttempts);
		Log.w(TAG, "Continuing standalone playback after PiP exit: " + reason);
		VideoUnit source = this.source;
		this.source = null;
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
		if (enteredPictureInPicture && exitedPictureInPicture && !isInPictureInPictureMode()) {
			resumedAfterPictureInPictureExit = true;
		}
		VideoDiagnostics.recordUi("pip on_resume in_pip=" + isInPictureInPictureMode()
				+ " exited=" + exitedPictureInPicture);
		handler.removeCallbacks(finishDismissedPictureInPicture);
		maybeReturnToGallery();
	}

	@Override
	protected void onUserLeaveHint() {
		super.onUserLeaveHint();
		if (standalonePlayback && player != null && !isInPictureInPictureMode()) {
			enterPictureInPicture();
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
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
		if (isInPictureInPictureMode) {
			handler.removeCallbacks(finishDismissedPictureInPicture);
			handler.removeCallbacks(returnToGalleryAfterExit);
			enteredPictureInPicture = true;
			exitedPictureInPicture = false;
			stoppedWhileInPictureInPicture = false;
			resumedAfterPictureInPictureExit = false;
			resumePlaybackAfterPictureInPictureExit = false;
			galleryRestorePrepared = false;
			returnToGalleryScheduled = false;
			standalonePlayback = false;
			returnToGalleryAttempts = 0;
			VideoDiagnostics.recordUi("pip mode_changed=true");
		} else if (enteredPictureInPicture) {
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
			VideoUnit source = this.source;
			boolean handled;
			if (!enteredPictureInPicture) {
				handled = source != null && source.restorePictureInPicturePlayer(player, position,
						playbackSpeed, muted, playing);
			} else {
				handled = source != null && source.closePictureInPicturePlayer(player);
			}
			if (!handled) {
				player.destroyAsync();
			}
			this.player = null;
			this.source = null;
		}
		hidePreviewFrameImmediately();
		VideoDiagnostics.recordUi("pip activity_destroyed entered=" + enteredPictureInPicture
				+ " exited=" + exitedPictureInPicture + " returned=" + returnedToGallery);
		overridePendingTransition(0, 0);
		super.onDestroy();
	}

	@Override
	public void onComplete(VideoPlayer player) {
		runOnUiThread(() -> {
			if (this.player == player) {
				if (Preferences.getVideoCompletionMode() == Preferences.VideoCompletionMode.LOOP) {
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
	public void onBusyStateChange(VideoPlayer player, boolean busy) {}

	@Override
	public void onDimensionChange(VideoPlayer player) {
		runOnUiThread(() -> {
			if (this.player == player) {
				updatePictureInPictureParams();
			}
		});
	}
}
