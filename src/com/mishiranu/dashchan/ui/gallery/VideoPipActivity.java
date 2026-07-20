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
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.VideoPlayer;
import com.mishiranu.dashchan.util.AudioFocus;
import java.io.File;
import java.util.Collections;

public class VideoPipActivity extends Activity implements VideoPlayer.Listener {
	private static final String EXTRA_FILE_PATH = "filePath";
	private static final String EXTRA_POSITION = "position";
	private static final String EXTRA_PLAYBACK_SPEED = "playbackSpeed";
	private static final String EXTRA_MUTED = "muted";
	private static final String EXTRA_PLAYING = "playing";
	private static final String ACTION_TOGGLE_PLAYBACK = VideoPipActivity.class.getName() + ".TOGGLE_PLAYBACK";
	private static final int REQUEST_TOGGLE_PLAYBACK = 1;

	private static final Object TRANSFER_LOCK = new Object();
	// The PiP activity runs in the same process, so it can reuse the initialized native player.
	private static PendingTransfer pendingTransfer;

	private static class PendingTransfer {
		public final VideoUnit source;
		public final VideoPlayer player;
		public final String filePath;

		private PendingTransfer(VideoUnit source, VideoPlayer player, String filePath) {
			this.source = source;
			this.player = player;
			this.filePath = filePath;
		}
	}

	static Intent createIntent(Context context, File file, long position, int playbackSpeed,
			boolean muted, boolean playing, VideoUnit source, VideoPlayer player) {
		synchronized (TRANSFER_LOCK) {
			pendingTransfer = new PendingTransfer(source, player, file.getAbsolutePath());
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

	private FrameLayout rootView;
	private VideoUnit source;
	private VideoPlayer player;
	private AudioFocus audioFocus;
	private int playbackSpeed;
	private boolean muted;
	private boolean startPlaying;
	private boolean enteredPictureInPicture;
	private boolean exitedPictureInPicture;
	private boolean returnedToGallery;
	private boolean receiverRegistered;
	private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (ACTION_TOGGLE_PLAYBACK.equals(intent.getAction())) {
				togglePlayback();
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		overridePendingTransition(0, 0);
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
		playbackSpeed = intent.getIntExtra(EXTRA_PLAYBACK_SPEED, 1000);
		muted = intent.getBooleanExtra(EXTRA_MUTED, false);
		startPlaying = intent.getBooleanExtra(EXTRA_PLAYING, true);

		getWindow().setStatusBarColor(Color.BLACK);
		getWindow().setNavigationBarColor(Color.BLACK);
		getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				| View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
		rootView = new FrameLayout(this);
		rootView.setBackgroundColor(Color.BLACK);
		setContentView(rootView);

		IntentFilter controlFilter = new IntentFilter(ACTION_TOGGLE_PLAYBACK);
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
		rootView.addView(player.getVideoView(this), new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
		player.setPlaybackSpeed(playbackSpeed);
		player.setMuted(muted);
		if (startPlaying && !muted && player.isAudioPresent()) {
			audioFocus.acquire();
		}
		player.setPlaying(startPlaying);
		rootView.setKeepScreenOn(startPlaying);
		rootView.post(this::enterPictureInPicture);
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
				finish();
			}
		} catch (IllegalArgumentException | IllegalStateException e) {
			finish();
		}
	}

	private PictureInPictureParams createPictureInPictureParams(Point dimensions) {
		PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
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
		Intent controlIntent = new Intent(ACTION_TOGGLE_PLAYBACK).setPackage(getPackageName());
		PendingIntent pendingIntent = PendingIntent.getBroadcast(this, REQUEST_TOGGLE_PLAYBACK, controlIntent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		RemoteAction action = new RemoteAction(Icon.createWithResource(this, iconResource), title, title, pendingIntent);
		builder.setActions(Collections.singletonList(action));
		return builder.build();
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

	private void maybeReturnToGallery() {
		if (exitedPictureInPicture && enteredPictureInPicture && !isInPictureInPictureMode()
				&& hasWindowFocus() && !returnedToGallery) {
			returnToGallery();
		}
	}

	private void returnToGallery() {
		VideoPlayer player = this.player;
		if (player == null || returnedToGallery) {
			return;
		}
		returnedToGallery = true;
		boolean playing = player.isPlaying();
		long position = player.getPosition();
		player.setPlaying(false);
		audioFocus.release();
		player.releaseVideoView();
		player.setListener(null);
		VideoUnit source = this.source;
		this.source = null;
		this.player = null;
		if (source == null || !source.restorePictureInPicturePlayer(player, position,
				playbackSpeed, muted, playing)) {
			player.destroy();
		}
		finish();
	}

	@Override
	protected void onResume() {
		super.onResume();
		maybeReturnToGallery();
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			maybeReturnToGallery();
		}
	}

	@Override
	public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
		if (isInPictureInPictureMode) {
			enteredPictureInPicture = true;
		} else if (enteredPictureInPicture) {
			exitedPictureInPicture = true;
		}
	}

	@Override
	protected void onDestroy() {
		if (receiverRegistered) {
			unregisterReceiver(controlReceiver);
			receiverRegistered = false;
		}
		if (audioFocus != null) {
			audioFocus.release();
		}
		VideoPlayer player = this.player;
		if (player != null) {
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
				player.destroy();
			}
			this.player = null;
			this.source = null;
		}
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
