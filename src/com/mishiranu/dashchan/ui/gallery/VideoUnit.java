package com.mishiranu.dashchan.ui.gallery;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.AudioManager;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentManager;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.AdvancedPreferences;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.ReadVideoTask;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.media.VideoPlayer;
import com.mishiranu.dashchan.media.VolumeGestureUtils;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.util.AnimationUtils;
import com.mishiranu.dashchan.util.AudioFocus;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.SummaryLayout;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

public class VideoUnit {
	enum PictureInPictureRestoreState {
		READY,
		TRANSFER_INACTIVE,
		PLAYER_MISMATCH,
		NOT_INITIALIZED,
		HOLDER_UNAVAILABLE
	}

	private static final int[] PLAYBACK_SPEEDS = {800, 1000, 1250, 1500, 2000, 4000};
	private static final String[] PLAYBACK_SPEED_LABELS = {"0.8x", "1x", "1.25x", "1.5x", "2x", "4x"};

	private final PagerInstance instance;
	private final LinearLayout controlsView;
	private final AudioFocus audioFocus;
	private final AudioManager audioManager;

	private int layoutConfiguration = -1;
	private boolean rightHandControls;
	private LinearLayout configurationView;
	private TextView timeTextView;
	private TextView totalTimeTextView;
	private SeekBar seekBar;
	private ImageButton playPauseButton;
	private TextView playbackSpeedButton;
	private ImageButton muteButton;
	private ImageButton pictureInPictureButton;
	private ImageButton fullscreenButton;
	private PopupMenu playbackSpeedPopupMenu;

	private VideoPlayer player;
	private BackgroundDrawable backgroundDrawable;
	private int playbackSpeed = 1000;
	private boolean muted;
	private boolean muteSupported = true;
	private boolean initialized;
	private boolean wasPlaying;
	private boolean pausedByTransientLossOfFocus;
	private boolean finishedPlayback;
	private boolean trackingNow;
	private boolean hideSurfaceOnInit;
	private boolean pictureInPictureTransferred;
	private int lastNonZeroSystemVolume;
	private int localVolume;
	private int lastNonZeroLocalVolume;
	private boolean localVolumeSupported = true;
	private boolean volumeGestureLocal;
	private int volumeGestureStart;
	private final RectF videoTransformRect = new RectF();
	private int volumeGestureCurrent;
	private int volumeGestureMaximum;
	private int volumeGestureSensitivity;
	private File sourceFile;

	private ReadVideoCallback readVideoCallback;
	private boolean playbackSpeedControl;
	private boolean pictureInPictureControl;
	private final View.OnLayoutChangeListener surfaceParentLayoutChangeListener;

	public VideoUnit(PagerInstance instance, AudioManager audioManager) {
		this.instance = instance;
		this.audioManager = audioManager;
		surfaceParentLayoutChangeListener = this::onSurfaceParentLayoutChanged;
		if (audioManager != null) {
			int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
			if (volume > 0) {
				lastNonZeroSystemVolume = volume;
			}
		}
		localVolume = Preferences.getVideoLocalVolume();
		lastNonZeroLocalVolume = localVolume > 0 ? localVolume
				: Preferences.DEFAULT_VIDEO_LOCAL_VOLUME_LEVEL;
		if (Preferences.isRememberVideoPlaybackSpeed() && Preferences.isPersistVideoPlaybackSpeed()) {
			playbackSpeed = normalizePlaybackSpeed(Preferences.getSavedVideoPlaybackSpeed());
		}
		controlsView = new LinearLayout(instance.galleryInstance.context);
		controlsView.setOrientation(LinearLayout.VERTICAL);
		controlsView.setVisibility(View.GONE);
		audioFocus = new AudioFocus(instance.galleryInstance.context, change -> {
			switch (change) {
				case LOSS: {
					setPlaying(false, false);
					updatePlayState();
					break;
				}
				case LOSS_TRANSIENT: {
					boolean playing = player.isPlaying();
					setPlaying(false, false);
					if (playing) {
						pausedByTransientLossOfFocus = true;
					}
					updatePlayState();
					break;
				}
				case GAIN: {
					if (pausedByTransientLossOfFocus) {
						setPlaying(true, false);
					}
					updatePlayState();
					break;
				}
			}
		});
	}

	public void addViews(FrameLayout frameLayout) {
		frameLayout.addView(controlsView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
	}

	public void onResume() {
		if (pictureInPictureTransferred) {
			return;
		}
		if (layoutConfiguration >= 0
				&& rightHandControls != Preferences.isVideoRightHandControls()) {
			recreateVideoControls();
		}
		if (player != null && initialized) {
			setPlaying(wasPlaying, true);
			updatePlayState();
		} else {
			wasPlaying = true;
		}
	}

	public void onPause() {
		if (pictureInPictureTransferred) {
			return;
		}
		if (player != null && initialized) {
			long duration = player.getDuration();
			if (finishedPlayback || duration > 0L && player.getPosition() >= duration) {
				markPlaybackFinished();
				preserveFinishedPlaybackFrame();
			} else {
				wasPlaying = player.isPlaying();
				setPlaying(false, true);
			}
		} else {
			wasPlaying = false;
		}
	}

	public void onConfigurationChanged(Configuration newConfig) {
		if (newConfig.orientation != Configuration.ORIENTATION_UNDEFINED) {
			if (layoutConfiguration != -1) {
				recreateVideoControls();
			}
		}
	}

	public void onApplyWindowInsets(int left, int right, int bottom) {
		controlsView.setPadding(left, 0, right, bottom);
	}

	public boolean isInitialized() {
		return initialized;
	}

	public boolean isAudioPresent() {
		return initialized && player != null && player.isAudioPresent();
	}

	public boolean isCreated() {
		return player != null;
	}

	public void interrupt() {
		dismissPlaybackSpeedPopupMenu();
		if (readVideoCallback != null) {
			readVideoCallback.cancel();
			readVideoCallback = null;
		}
		if (initialized && !pictureInPictureTransferred) {
			audioFocus.release();
			initialized = false;
		}
		invalidateControlsVisibility();
		if (player != null && !pictureInPictureTransferred) {
			VideoPlayer workPlayer = player;
			player = null;
			workPlayer.setPlaying(false);
			workPlayer.releaseVideoViewAndDestroyAsync();
			instance.currentHolder.progressBar.setVisible(false, false);
		}
		if (pictureInPictureTransferred) {
			pictureInPictureTransferred = false;
			player = null;
			initialized = false;
		}
		sourceFile = null;
		if (backgroundDrawable != null) {
			backgroundDrawable.recycle();
			backgroundDrawable = null;
		}
		interruptHolder(instance.leftHolder);
		interruptHolder(instance.currentHolder);
		interruptHolder(instance.rightHolder);
	}

	private void interruptHolder(PagerInstance.ViewHolder holder) {
		if (holder != null) {
			holder.surfaceParent.removeOnLayoutChangeListener(surfaceParentLayoutChangeListener);
			for (int i = 0; i < holder.surfaceParent.getChildCount(); i++) {
				resetVideoTransform(holder.surfaceParent.getChildAt(i));
			}
			holder.surfaceParent.removeAllViews();
		}
	}

	private void onSurfaceParentLayoutChanged(View view, int left, int top, int right, int bottom,
			int oldLeft, int oldTop, int oldRight, int oldBottom) {
		if (right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop) {
			return;
		}
		PagerInstance.ViewHolder holder = instance.currentHolder;
		if (!initialized || player == null || holder == null || holder.surfaceParent != view
				|| holder.surfaceParent.getChildCount() != 1) {
			return;
		}
		View videoView = holder.surfaceParent.getChildAt(0);
		if (videoView != player.getVideoView(instance.galleryInstance.context)) {
			return;
		}
		resetVideoTransform(videoView);
		if (Preferences.isVideoZoomGesturesEnabled()) {
			captureAndApplyVideoTransform(holder, videoView);
		}
	}

	private void attachSurfaceParentLayoutListener(PagerInstance.ViewHolder holder) {
		holder.surfaceParent.removeOnLayoutChangeListener(surfaceParentLayoutChangeListener);
		holder.surfaceParent.addOnLayoutChangeListener(surfaceParentLayoutChangeListener);
	}

	private static void resetVideoTransform(View videoView) {
		videoView.setPivotX(0f);
		videoView.setPivotY(0f);
		videoView.setScaleX(1f);
		videoView.setScaleY(1f);
		videoView.setTranslationX(0f);
		videoView.setTranslationY(0f);
	}

	public void applyVideoTransform(com.mishiranu.dashchan.widget.PhotoView photoView,
			float left, float top, float right, float bottom) {
		PagerInstance.ViewHolder holder = instance.currentHolder;
		if (!initialized || !Preferences.isVideoZoomGesturesEnabled() || holder == null
				|| holder.photoView != photoView || player == null) {
			return;
		}
		videoTransformRect.set(left, top, right, bottom);
		View videoView = player.getVideoView(instance.galleryInstance.context);
		applyVideoTransform(videoView);
	}

	private void applyVideoTransform(View videoView) {
		if (videoView.getWidth() <= 0 || videoView.getHeight() <= 0 || videoTransformRect.isEmpty()) {
			return;
		}
		videoView.setPivotX(0f);
		videoView.setPivotY(0f);
		videoView.setScaleX(videoTransformRect.width() / videoView.getWidth());
		videoView.setScaleY(videoTransformRect.height() / videoView.getHeight());
		videoView.setTranslationX(videoTransformRect.left - videoView.getLeft());
		videoView.setTranslationY(videoTransformRect.top - videoView.getTop());
	}

	private void captureAndApplyVideoTransform(PagerInstance.ViewHolder holder, View videoView) {
		if (initialized && player != null && holder == instance.currentHolder && holder.photoView != null
				&& videoView == player.getVideoView(instance.galleryInstance.context)
				&& holder.photoView.getImageDisplayRect(videoTransformRect) != null) {
			applyVideoTransform(videoView);
		}
	}

	public void forcePause() {
		wasPlaying = false;
		if (initialized) {
			setPlaying(false, true);
		}
	}

	public void applyVideo(Uri uri, File file, boolean reload) {
		sourceFile = file;
		wasPlaying = true;
		finishedPlayback = false;
		hideSurfaceOnInit = false;
		if (!Preferences.isVideoPlaybackSpeedControl() ||
				(!reload && !Preferences.isRememberVideoPlaybackSpeed())) {
			playbackSpeed = 1000;
		}
		dismissPlaybackSpeedPopupMenu();
		boolean seekAnyFrame = Preferences.isVideoSeekAnyFrame();
		VideoPlayer player = new VideoPlayer(playerListener, seekAnyFrame);
		boolean loadedFromFile = false;
		if (!reload && file.exists()) {
			try {
				player.init(file, null);
				loadedFromFile = true;
			} catch (IOException e) {
				// Player was consumed, create a new one and try to download a new video file
				player = new VideoPlayer(playerListener, seekAnyFrame);
			}
		}
		this.player = player;
		if (loadedFromFile) {
			initializePlayer();
			seekBar.setSecondaryProgress(seekBar.getMax());
			if (instance.currentHolder.mediaSummary.updateSize(file.length())) {
				instance.galleryInstance.callback.updateTitle();
			}
			instance.currentHolder.loadState = PagerInstance.LoadState.COMPLETE;
			updatePictureInPictureButton();
			instance.galleryInstance.callback.invalidateOptionsMenu();
		} else {
			instance.currentHolder.progressBar.setIndeterminate(true);
			instance.currentHolder.progressBar.setVisible(true, false);
			readVideoCallback = new ReadVideoCallback(player, instance.currentHolder,
					instance.galleryInstance.chanName, uri);
		}
	}

	private boolean setPlaying(boolean playing, boolean resetFocus) {
		if (player.isPlaying() != playing) {
			if (resetFocus && player.isAudioPresent() && !muted) {
				if (playing) {
					if (!audioFocus.acquire()) {
						return false;
					}
				} else {
					audioFocus.release();
				}
			}
			player.setPlaying(playing);
			pausedByTransientLossOfFocus = false;
		}
		return true;
	}

	private void initializePlayer() {
		PagerInstance.ViewHolder holder = instance.currentHolder;
		holder.progressBar.setVisible(false, false);
		Point dimensions = player.getDimensions();
		if (holder.mediaSummary.updateDimensions(dimensions.x, dimensions.y)) {
			instance.galleryInstance.callback.updateTitle();
		}
		backgroundDrawable = new BackgroundDrawable();
		backgroundDrawable.width = dimensions.x;
		backgroundDrawable.height = dimensions.y;
		holder.recyclePhotoView();
		boolean zoomGestures = Preferences.isVideoZoomGesturesEnabled();
		holder.photoView.setImage(backgroundDrawable, false, !zoomGestures, false, zoomGestures ? 10f : 0f);
		View videoView = player.getVideoView(instance.galleryInstance.context);
		resetVideoTransform(videoView);
		holder.surfaceParent.setClickable(false);
		holder.surfaceParent.setFocusable(false);
		attachSurfaceParentLayoutListener(holder);
		holder.surfaceParent.addView(videoView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
		applyPlayerVolume(player);
		muteSupported = !player.isAudioPresent() || player.setMuted(muted);
		if (!muteSupported) {
			muted = false;
		}
		recreateVideoControls();
		playPauseButton.setEnabled(true);
		seekBar.setEnabled(true);
		initialized = true;
		if (zoomGestures) {
			videoView.post(() -> captureAndApplyVideoTransform(holder, videoView));
		}
		setPlaybackSpeed(playbackSpeed);
		pausedByTransientLossOfFocus = false;
		if (hideSurfaceOnInit) {
			showHideVideoView(false);
		}
		invalidateControlsVisibility();
		setPlaying(wasPlaying, true);
		updatePlayState();
	}

	private void recreateVideoControls() {
		Context context = instance.galleryInstance.context;
		float density = ResourceUtils.obtainDensity(context);
		int targetLayoutCounfiguration = ResourceUtils.isTabletOrLandscape(context.getResources()
				.getConfiguration()) ? 1 : 0;
		boolean speedControl = Preferences.isVideoPlaybackSpeedControl();
		boolean pictureInPictureControl = Preferences.isVideoPictureInPicture() && context.getPackageManager()
				.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
		boolean rightHandControls = Preferences.isVideoRightHandControls();
		if (targetLayoutCounfiguration != layoutConfiguration || speedControl != playbackSpeedControl
				|| pictureInPictureControl != this.pictureInPictureControl
				|| rightHandControls != this.rightHandControls) {
			boolean firstTimeLayout = layoutConfiguration < 0;
			layoutConfiguration = targetLayoutCounfiguration;
			playbackSpeedControl = speedControl;
			this.pictureInPictureControl = pictureInPictureControl;
			this.rightHandControls = rightHandControls;
			boolean longLayout = targetLayoutCounfiguration == 1;

			controlsView.removeAllViews();
			if (seekBar != null) {
				seekBar.removeCallbacks(progressRunnable);
			}
			trackingNow = false;
			playbackSpeedButton = null;
			muteButton = null;
			pictureInPictureButton = null;
			fullscreenButton = null;

			configurationView = new LinearLayout(context);
			configurationView.setOrientation(LinearLayout.HORIZONTAL);
			configurationView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
			configurationView.setPadding((int) (8f * density), 0, (int) (8f * density), 0);
			controlsView.addView(configurationView, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);

			LinearLayout controls = new LinearLayout(context);
			controls.setOrientation(longLayout ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
			controls.setBackgroundColor(instance.galleryInstance.actionBarColor);
			controls.setPadding((int) (8f * density), longLayout ? 0 : (int) (8f * density), (int) (8f * density), 0);
			controls.setClickable(true);
			controlsView.addView(controls, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);

			CharSequence oldTimeText = timeTextView != null ? timeTextView.getText() : null;
			timeTextView = new TextView(context, null, android.R.attr.textAppearanceListItem);
			ThemeEngine.applyStyle(timeTextView);
			ViewUtils.setTextSizeScaled(timeTextView, 14);
			timeTextView.setGravity(Gravity.CENTER_HORIZONTAL);
			timeTextView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);
			if (oldTimeText != null) {
				timeTextView.setText(oldTimeText);
			}

			totalTimeTextView = new TextView(context, null, android.R.attr.textAppearanceListItem);
			ThemeEngine.applyStyle(totalTimeTextView);
			ViewUtils.setTextSizeScaled(totalTimeTextView, 14);
			totalTimeTextView.setGravity(Gravity.CENTER_HORIZONTAL);
			totalTimeTextView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);

			int oldSecondaryProgress = seekBar != null ? seekBar.getSecondaryProgress() : -1;
			seekBar = new SeekBar(context);
			seekBar.setOnSeekBarChangeListener(seekBarListener);
			if (oldSecondaryProgress >= 0) {
				seekBar.setSecondaryProgress(oldSecondaryProgress);
			}

			playPauseButton = new ImageButton(context, null, android.R.attr.borderlessButtonStyle);
			playPauseButton.setScaleType(ImageButton.ScaleType.CENTER);
			playPauseButton.setOnClickListener(playPauseClickListener);

			if (playbackSpeedControl) {
				playbackSpeedButton = new TextView(context, null, android.R.attr.textAppearanceListItem);
				ThemeEngine.applyStyle(playbackSpeedButton);
				ViewUtils.setTextSizeScaled(playbackSpeedButton, 14);
				playbackSpeedButton.setGravity(Gravity.CENTER);
				playbackSpeedButton.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);
				playbackSpeedButton.setContentDescription(context.getString(R.string.playback_speed));
				playbackSpeedButton.setPadding((int) (8f * density), 0, (int) (8f * density), 0);
				ViewUtils.setSelectableItemBackground(playbackSpeedButton);
				playbackSpeedButton.setOnClickListener(playbackSpeedClickListener);
				updatePlaybackSpeedButton();
			}
			muteButton = new ImageButton(context, null, android.R.attr.borderlessButtonStyle);
			muteButton.setScaleType(ImageButton.ScaleType.CENTER);
			muteButton.setOnClickListener(muteClickListener);
			if (pictureInPictureControl) {
				pictureInPictureButton = new ImageButton(context, null, android.R.attr.borderlessButtonStyle);
				pictureInPictureButton.setScaleType(ImageButton.ScaleType.CENTER);
				pictureInPictureButton.setImageResource(R.drawable.ic_picture_in_picture);
				pictureInPictureButton.setContentDescription(context.getString(R.string.enter_picture_in_picture));
				pictureInPictureButton.setOnClickListener(pictureInPictureClickListener);
			}
			fullscreenButton = new ImageButton(context, null, android.R.attr.borderlessButtonStyle);
			fullscreenButton.setScaleType(ImageButton.ScaleType.CENTER);
			fullscreenButton.setOnClickListener(fullscreenClickListener);
			updateFullscreenButton();

			if (longLayout) {
				controls.setGravity(Gravity.CENTER_VERTICAL);
				controls.addView(timeTextView, (int) (48f * density), LinearLayout.LayoutParams.WRAP_CONTENT);
				controls.addView(seekBar, new LinearLayout.LayoutParams(0,
						LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
				controls.addView(playPauseButton, (int) (80f * density), LinearLayout.LayoutParams.WRAP_CONTENT);
				controls.addView(totalTimeTextView, (int) (48f * density),
						LinearLayout.LayoutParams.WRAP_CONTENT);
				controls.addView(fullscreenButton, (int) (48f * density),
						LinearLayout.LayoutParams.WRAP_CONTENT);
			} else {
				LinearLayout controls1 = new LinearLayout(context);
				controls1.setOrientation(LinearLayout.HORIZONTAL);
				controls1.setGravity(Gravity.CENTER_VERTICAL);
				controls1.setPadding(0, (int) (8f * density), 0, (int) (8f * density));
				LinearLayout controls2 = new LinearLayout(context);
				controls2.setOrientation(LinearLayout.HORIZONTAL);
				controls2.setGravity(Gravity.CENTER_VERTICAL);
				controls.addView(controls1, LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT);
				controls.addView(controls2, LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT);
				controls1.addView(seekBar, LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT);
				controls2.addView(timeTextView, new LinearLayout.LayoutParams(0,
						LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
				controls2.addView(playPauseButton, (int) (80f * density), LinearLayout.LayoutParams.WRAP_CONTENT);
				controls2.addView(totalTimeTextView, new LinearLayout.LayoutParams(0,
						LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
				controls2.addView(fullscreenButton, (int) (48f * density),
						LinearLayout.LayoutParams.WRAP_CONTENT);
			}
			if (firstTimeLayout) {
				AnimationUtils.measureDynamicHeight(controlsView);
				controlsView.setTranslationY(controlsView.getMeasuredHeight());
				controlsView.setAlpha(0f);
			}
		}
		if (player != null) {
			configurationView.removeAllViews();
			View spacer = new View(context);
			if (rightHandControls) {
				configurationView.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
				configurationView.addView(muteButton, (int) (48f * density), (int) (48f * density));
				if (pictureInPictureButton != null) {
					configurationView.addView(pictureInPictureButton, (int) (48f * density),
							(int) (48f * density));
				}
				if (playbackSpeedButton != null) {
					configurationView.addView(playbackSpeedButton, (int) (56f * density),
							(int) (48f * density));
				}
			} else {
				if (playbackSpeedButton != null) {
					configurationView.addView(playbackSpeedButton, (int) (56f * density),
							(int) (48f * density));
				}
				if (pictureInPictureButton != null) {
					configurationView.addView(pictureInPictureButton, (int) (48f * density),
							(int) (48f * density));
				}
				configurationView.addView(muteButton, (int) (48f * density), (int) (48f * density));
				configurationView.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
			}
			updateMuteButton();
			updatePictureInPictureButton();
			updateDuration(player.getDuration());
		}
		seekBar.removeCallbacks(progressRunnable);
		seekBar.post(progressRunnable);
		updatePlayState();
	}

	private static String formatVideoTime(long position) {
		position /= 1000;
		int m = (int) (position / 60 % 60);
		int s = (int) (position % 60);
		return String.format(Locale.US, "%02d:%02d", m, s);
	}

	private void updateDuration(long duration) {
		duration = Math.max(duration, 0L);
		int maximum = (int) Math.min(duration, Integer.MAX_VALUE);
		int progress = Math.min(seekBar.getProgress(), maximum);
		int secondaryProgress = Math.min(seekBar.getSecondaryProgress(), maximum);
		totalTimeTextView.setText(formatVideoTime(duration));
		seekBar.setMax(maximum);
		seekBar.setProgress(progress);
		seekBar.setSecondaryProgress(secondaryProgress);
	}

	private static String formatPlaybackSpeed(int speed) {
		for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
			if (PLAYBACK_SPEEDS[i] == speed) {
				return PLAYBACK_SPEED_LABELS[i];
			}
		}
		return "1x";
	}

	private static int normalizePlaybackSpeed(int speed) {
		for (int playbackSpeed : PLAYBACK_SPEEDS) {
			if (playbackSpeed == speed) {
				return speed;
			}
		}
		return 1000;
	}

	private void updatePlaybackSpeedButton() {
		if (playbackSpeedButton != null) {
			playbackSpeedButton.setText(formatPlaybackSpeed(playbackSpeed));
		}
	}

	private void setPlaybackSpeed(int playbackSpeed) {
		this.playbackSpeed = playbackSpeed;
		updatePlaybackSpeedButton();
		if (player != null && initialized) {
			player.setPlaybackSpeed(playbackSpeed);
		}
	}

	private void dismissPlaybackSpeedPopupMenu() {
		if (playbackSpeedPopupMenu != null) {
			PopupMenu popupMenu = playbackSpeedPopupMenu;
			playbackSpeedPopupMenu = null;
			popupMenu.dismiss();
		}
	}

	private final View.OnClickListener playbackSpeedClickListener = new View.OnClickListener() {
		@Override
		public void onClick(View v) {
			if (!Preferences.isVideoPlaybackSpeedControl()) {
				return;
			}
			if (playbackSpeedPopupMenu != null) {
				dismissPlaybackSpeedPopupMenu();
				return;
			}
			Context context = v.getContext();
			int resId = ResourceUtils.getResourceId(context, android.R.attr.popupTheme, 0);
			Context popupContext = resId != 0 ? new ContextThemeWrapper(context, resId) : context;
			PopupMenu popupMenu = new PopupMenu(popupContext, v, Gravity.START, 0,
					R.style.Widget_OverlapPopupMenu);
			popupMenu.getMenu().setGroupCheckable(0, true, true);
			for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
				popupMenu.getMenu().add(0, i, i, PLAYBACK_SPEED_LABELS[i])
						.setCheckable(true).setChecked(PLAYBACK_SPEEDS[i] == playbackSpeed);
			}
			popupMenu.setOnMenuItemClickListener(item -> {
				int playbackSpeed = PLAYBACK_SPEEDS[item.getItemId()];
				setPlaybackSpeed(playbackSpeed);
				if (Preferences.isRememberVideoPlaybackSpeed() &&
						Preferences.isPersistVideoPlaybackSpeed()) {
					Preferences.setSavedVideoPlaybackSpeed(playbackSpeed);
				}
				return true;
			});
			popupMenu.setOnDismissListener(menu -> {
				if (playbackSpeedPopupMenu == popupMenu) {
					playbackSpeedPopupMenu = null;
				}
			});
			playbackSpeedPopupMenu = popupMenu;
			popupMenu.show();
		}
	};

	private void updateMuteButton() {
		if (muteButton != null) {
			boolean audioPresent = player != null && player.isAudioPresent();
			boolean systemMuted = audioManager != null
					&& audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0;
			boolean localVolumeActive = isLocalVolumeActive();
			boolean effectiveMuted = muted || (localVolumeActive ? localVolume <= 0 : systemMuted);
			boolean enabled = audioPresent && (muteSupported || (!localVolumeActive && systemMuted));
			boolean showMuted = !audioPresent || effectiveMuted;
			Context context = muteButton.getContext();
			muteButton.setEnabled(enabled);
			muteButton.setActivated(enabled && effectiveMuted);
			muteButton.setAlpha(enabled ? 1f : 0.45f);
			muteButton.setImageResource(ResourceUtils.getResourceId(context,
					showMuted ? R.attr.iconActionVolumeOff : R.attr.iconActionVolumeOn, 0));
			muteButton.setImageTintList(ColorStateList.valueOf(enabled && effectiveMuted
					? 0xffff5252 : Color.WHITE));
			muteButton.setContentDescription(context.getString(!audioPresent ? R.string.video_has_no_audio
					: effectiveMuted ? R.string.unmute_video : R.string.mute_video));
		}
	}

	private boolean isLocalVolumeActive() {
		return Preferences.isVideoLocalVolume() && localVolumeSupported;
	}

	private int getLocalVolumeBoostDb() {
		return Preferences.isVideoAudioBoost() ? Preferences.getVideoAudioBoostDb() : 0;
	}

	private boolean setPlayerLocalVolume(int volume) {
		return player.setVolume(volume, getLocalVolumeBoostDb());
	}

	private void applyPlayerVolume(VideoPlayer player) {
		if (player == null || !player.isAudioPresent()) {
			localVolumeSupported = true;
			return;
		}
		int volume = Preferences.isVideoLocalVolume() ? localVolume : 100;
		localVolumeSupported = player.setVolume(volume,
				Preferences.isVideoLocalVolume() ? getLocalVolumeBoostDb() : 0);
	}

	int onVolumeGestureStart() {
		if (!initialized || player == null || !player.isAudioPresent()) {
			return -1;
		}
		volumeGestureLocal = isLocalVolumeActive();
		if (volumeGestureLocal) {
			if (!setPlayerLocalVolume(localVolume)) {
				localVolumeSupported = false;
				volumeGestureLocal = false;
			}
		} else if (localVolumeSupported) {
			// The legacy mode controls STREAM_MUSIC. Remove a previously applied
			// per-player attenuation so the two volume scales are never multiplied.
			player.setVolume(100, 0);
		}
		if (volumeGestureLocal) {
			volumeGestureMaximum = 100;
			volumeGestureStart = localVolume;
		} else {
			if (audioManager == null || audioManager.isVolumeFixed()) {
				return -1;
			}
			volumeGestureMaximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
			if (volumeGestureMaximum <= 0) {
				return -1;
			}
			volumeGestureStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
			if (volumeGestureStart > 0) {
				lastNonZeroSystemVolume = volumeGestureStart;
			}
		}
		volumeGestureCurrent = volumeGestureStart;
		volumeGestureSensitivity = Preferences.getVideoVolumeGestureSensitivity();
		updateMuteButton();
		return Math.round(100f * volumeGestureCurrent / volumeGestureMaximum);
	}

	int onVolumeGestureProgress(float distanceFraction) {
		int volume = VolumeGestureUtils.calculateVolume(volumeGestureStart, volumeGestureMaximum,
				distanceFraction, volumeGestureSensitivity);
		if (volume != volumeGestureCurrent) {
			if (volumeGestureLocal) {
				if (!setPlayerLocalVolume(volume)) {
					localVolumeSupported = false;
					return Math.round(100f * volumeGestureCurrent / volumeGestureMaximum);
				}
				localVolume = volume;
				if (volume > 0) {
					lastNonZeroLocalVolume = volume;
				}
			} else {
				audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
				if (volume > 0) {
					lastNonZeroSystemVolume = volume;
				}
			}
			volumeGestureCurrent = volume;
			if (volume > 0 && muted) {
				setMuted(false);
			} else {
				updateMuteButton();
			}
		}
		return Math.round(100f * volumeGestureCurrent / volumeGestureMaximum);
	}

	void onVolumeGestureEnd() {
		if (volumeGestureLocal) {
			Preferences.setVideoLocalVolume(localVolume);
		}
		updateMuteButton();
	}

	boolean isVolumeGestureLocal() {
		return volumeGestureLocal;
	}

	private void setMuted(boolean muted) {
		if (player == null || !initialized || !player.isAudioPresent() || !muteSupported || this.muted == muted) {
			return;
		}
		boolean acquiredFocus = false;
		if (!muted && player.isPlaying()) {
			acquiredFocus = audioFocus.acquire();
			if (!acquiredFocus) {
				return;
			}
		}
		if (player.setMuted(muted)) {
			this.muted = muted;
			if (muted) {
				audioFocus.release();
			}
		} else {
			if (acquiredFocus) {
				audioFocus.release();
			}
			muteSupported = false;
		}
		updateMuteButton();
	}

	private final View.OnClickListener muteClickListener = v -> handleMuteClick();

	private void updatePictureInPictureButton() {
		if (pictureInPictureButton != null) {
			boolean enabled = initialized && player != null && sourceFile != null && sourceFile.isFile()
					&& instance.currentHolder != null
					&& instance.currentHolder.loadState == PagerInstance.LoadState.COMPLETE;
			pictureInPictureButton.setEnabled(enabled);
			pictureInPictureButton.setAlpha(enabled ? 1f : 0.45f);
		}
	}

	private final View.OnClickListener pictureInPictureClickListener = this::handlePictureInPictureClick;

	private void handlePictureInPictureClick(View v) {
		enterPictureInPicture(v.getContext(), false);
	}

	boolean enterPictureInPictureIfPlaying() {
		return Preferences.isVideoPictureInPicture() && Preferences.isVideoPictureInPictureAuto()
				&& enterPictureInPicture(instance.galleryInstance.context, true);
	}

	private boolean enterPictureInPicture(Context context, boolean requirePlaying) {
		if (pictureInPictureTransferred || player == null || !initialized || sourceFile == null
				|| !sourceFile.isFile() || instance.currentHolder == null
				|| instance.currentHolder.loadState != PagerInstance.LoadState.COMPLETE
				|| requirePlaying && !player.isPlaying()) {
			return false;
		}
		boolean playing = player.isPlaying();
		long position = player.getPosition();
		Bitmap previewFrame = createPictureInPicturePreview();
		VideoPlayer transferredPlayer = player;
		Intent intent = VideoPipActivity.createIntent(context, sourceFile, position,
				playbackSpeed, muted, playing, this, transferredPlayer, previewFrame);
		wasPlaying = false;
		setPlaying(false, true);
		transferredPlayer.releaseVideoView();
		pictureInPictureTransferred = true;
		instance.galleryInstance.callback.setGalleryVisibleForPictureInPicture(false);
		try {
			instance.galleryInstance.callback.getWindow().getContext().startActivity(intent);
			return true;
		} catch (RuntimeException e) {
			VideoPipActivity.cancelPendingTransfer(this, transferredPlayer);
			restorePictureInPicturePlayer(transferredPlayer, position, playbackSpeed, muted, playing);
			Toast.makeText(context, R.string.unknown_error, Toast.LENGTH_SHORT).show();
			return false;
		}
	}

	private Bitmap createPictureInPicturePreview() {
		Bitmap frame = player != null ? player.getCurrentFrame() : null;
		if (frame == null) {
			return null;
		}
		int width = frame.getWidth();
		int height = frame.getHeight();
		int maximumSize = 720;
		if (width <= maximumSize && height <= maximumSize) {
			return frame;
		}
		float scale = (float) maximumSize / Math.max(width, height);
		Bitmap scaled = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(width * scale)),
				Math.max(1, Math.round(height * scale)), true);
		if (scaled != frame) {
			frame.recycle();
		}
		return scaled;
	}

	boolean restorePictureInPicturePlayer(VideoPlayer transferredPlayer, long position, int playbackSpeed,
			boolean muted, boolean playing) {
		if (getPictureInPictureRestoreState(transferredPlayer) != PictureInPictureRestoreState.READY) {
			return false;
		}
		transferredPlayer.releaseVideoView();
		transferredPlayer.setListener(playerListener);
		View videoView = transferredPlayer.getVideoView(instance.galleryInstance.context);
		resetVideoTransform(videoView);
		attachSurfaceParentLayoutListener(instance.currentHolder);
		instance.currentHolder.surfaceParent.addView(videoView, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
		pictureInPictureTransferred = false;
		if (Preferences.isVideoZoomGesturesEnabled()) {
			PagerInstance.ViewHolder holder = instance.currentHolder;
			videoView.post(() -> captureAndApplyVideoTransform(holder, videoView));
		}
		this.playbackSpeed = normalizePlaybackSpeed(playbackSpeed);
		this.muted = muted;
		transferredPlayer.setPlaybackSpeed(this.playbackSpeed);
		applyPlayerVolume(transferredPlayer);
		muteSupported = !transferredPlayer.isAudioPresent() || transferredPlayer.setMuted(muted);
		instance.galleryInstance.callback.setGalleryVisibleForPictureInPicture(true);
		if (seekBar != null) {
			seekBar.setProgress((int) position);
		}
		if (timeTextView != null) {
			timeTextView.setText(formatVideoTime(position));
		}
		updatePlaybackSpeedButton();
		updateMuteButton();
		finishedPlayback = false;
		wasPlaying = playing;
		setPlaying(playing, true);
		updatePlayState();
		return true;
	}

	boolean preparePictureInPicturePlayerRestore(VideoPlayer transferredPlayer) {
		PictureInPictureRestoreState state = getPictureInPictureRestoreState(transferredPlayer);
		if (state != PictureInPictureRestoreState.READY
				&& state != PictureInPictureRestoreState.HOLDER_UNAVAILABLE) {
			return false;
		}
		instance.galleryInstance.callback.setGalleryVisibleForPictureInPicture(true);
		return true;
	}

	PictureInPictureRestoreState getPictureInPictureRestoreState(VideoPlayer transferredPlayer) {
		if (!pictureInPictureTransferred) {
			return PictureInPictureRestoreState.TRANSFER_INACTIVE;
		}
		if (player != transferredPlayer) {
			return PictureInPictureRestoreState.PLAYER_MISMATCH;
		}
		if (!initialized) {
			return PictureInPictureRestoreState.NOT_INITIALIZED;
		}
		if (instance.currentHolder == null) {
			return PictureInPictureRestoreState.HOLDER_UNAVAILABLE;
		}
		return PictureInPictureRestoreState.READY;
	}

	boolean detachPictureInPicturePlayer(VideoPlayer transferredPlayer) {
		if (!pictureInPictureTransferred || player != transferredPlayer) {
			return false;
		}
		pictureInPictureTransferred = false;
		player = null;
		initialized = false;
		wasPlaying = false;
		sourceFile = null;
		audioFocus.release();
		instance.galleryInstance.callback.closeGallery();
		return true;
	}

	void bringGalleryToForeground(Context context) {
		instance.galleryInstance.callback.bringGalleryToForeground(context);
	}

	boolean closePictureInPicturePlayer(VideoPlayer transferredPlayer) {
		if (!pictureInPictureTransferred || player != transferredPlayer) {
			return false;
		}
		pictureInPictureTransferred = false;
		audioFocus.release();
		transferredPlayer.setListener(null);
		transferredPlayer.setPlaying(false);
		transferredPlayer.releaseVideoViewAndDestroyAsync();
		player = null;
		initialized = false;
		wasPlaying = false;
		sourceFile = null;
		instance.galleryInstance.callback.closeGallery();
		return true;
	}

	private void handleMuteClick() {
		if (isLocalVolumeActive()) {
			if (localVolume <= 0) {
				int volume = Math.max(1, Math.min(lastNonZeroLocalVolume, 100));
				if (setPlayerLocalVolume(volume)) {
					localVolume = volume;
					lastNonZeroLocalVolume = volume;
					Preferences.setVideoLocalVolume(volume);
					if (muted) {
						setMuted(false);
					} else {
						updateMuteButton();
					}
				} else {
					localVolumeSupported = false;
					updateMuteButton();
				}
			} else {
				setMuted(!muted);
			}
			return;
		}
		if (audioManager != null && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
			int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
			int volume = lastNonZeroSystemVolume > 0 ? Math.min(lastNonZeroSystemVolume, maximum)
					: Math.max(1, Math.round(maximum * 0.5f));
			audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
			lastNonZeroSystemVolume = volume;
			if (muted) {
				setMuted(false);
			} else {
				updateMuteButton();
			}
		} else {
			if (!muted && audioManager != null) {
				int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
				if (volume > 0) {
					lastNonZeroSystemVolume = volume;
				}
			}
			setMuted(!muted);
		}
	}

	boolean togglePlayback() {
		if (!initialized || player == null) {
			return false;
		}
		if (finishedPlayback) {
			finishedPlayback = false;
			player.setPosition(0);
			restoreVideoViewAfterFinishedPlayback();
			setPlaying(true, true);
		} else {
			boolean playing = !player.isPlaying();
			setPlaying(playing, true);
		}
		updatePlayState();
		return true;
	}

	private final View.OnClickListener playPauseClickListener = v -> togglePlayback();

	private final View.OnClickListener fullscreenClickListener = v -> toggleFullscreen();

	private void toggleFullscreen() {
		boolean fullscreen = instance.galleryInstance.callback.isVideoFullscreen();
		if (fullscreen) {
			instance.galleryInstance.callback.setVideoFullscreen(false, false);
		} else if (player != null) {
			instance.galleryInstance.callback.setVideoFullscreen(true, true);
		}
		updateFullscreenButton();
	}

	private void updateFullscreenButton() {
		if (fullscreenButton != null) {
			boolean fullscreen = instance.galleryInstance.callback.isVideoFullscreen();
			fullscreenButton.setImageResource(fullscreen
					? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
			fullscreenButton.setContentDescription(instance.galleryInstance.context.getString(fullscreen
					? R.string.exit_fullscreen : R.string.enter_fullscreen));
		}
	}

	public static class SeekResult {
		public final long position;
		public final long duration;

		private SeekResult(long position, long duration) {
			this.position = position;
			this.duration = duration;
		}
	}

	public SeekResult seekBy(long offset) {
		if (!initialized || player == null) {
			return null;
		}
		long duration = player.getDuration();
		if (duration <= 0L) {
			return null;
		}
		long position = player.getPosition();
		long nextPosition = Math.max(0L, Math.min(duration, position + offset));
		player.setPosition(nextPosition);
		seekBar.setProgress((int) nextPosition);
		timeTextView.setText(formatVideoTime(nextPosition));
		if (finishedPlayback && nextPosition < duration) {
			finishedPlayback = false;
			restoreVideoViewAfterFinishedPlayback();
			updatePlayState();
		}
		return new SeekResult(nextPosition, duration);
	}

	private final Runnable progressRunnable = new Runnable() {
		@Override
		public void run() {
			if (initialized) {
				int position;
				if (trackingNow) {
					position = seekBar.getProgress();
				} else {
					position = (int) player.getPosition();
					seekBar.setProgress(position);
				}
				timeTextView.setText(formatVideoTime(position));
			}
			seekBar.postDelayed(this, 200);
		}
	};

	private final SeekBar.OnSeekBarChangeListener seekBarListener = new SeekBar.OnSeekBarChangeListener() {
		private int nextSeekPosition;

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
			trackingNow = false;
			seekBar.removeCallbacks(progressRunnable);
			if (nextSeekPosition != -1) {
				seekBar.setProgress(nextSeekPosition);
				player.setPosition(nextSeekPosition);
				seekBar.postDelayed(progressRunnable, 250);
				if (finishedPlayback && nextSeekPosition < player.getDuration()) {
					finishedPlayback = false;
					restoreVideoViewAfterFinishedPlayback();
					updatePlayState();
				}
			} else {
				progressRunnable.run();
			}
		}

		@Override
		public void onStartTrackingTouch(SeekBar seekBar) {
			trackingNow = true;
			seekBar.removeCallbacks(progressRunnable);
			nextSeekPosition = -1;
		}

		@Override
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			if (fromUser) {
				nextSeekPosition = progress;
			}
		}
	};

	private void updatePlayState() {
		if (player != null) {
			boolean playing = player.isPlaying();
			playPauseButton.setImageResource(ResourceUtils
					.getResourceId(instance.galleryInstance.context, finishedPlayback ? R.attr.iconButtonRefresh
							: playing ? R.attr.iconButtonPause : R.attr.iconButtonPlay, 0));
			instance.galleryInstance.callback.setScreenOnFixed(!finishedPlayback && playing);
		}
	}

	public void viewMetadata() {
		if (initialized) {
			Map<String, String> metadata = player.getMetadata();
			showMetadata(instance.galleryInstance.callback.getChildFragmentManager(), metadata);
		}
	}

	private static void showMetadata(FragmentManager fragmentManager, Map<String, String> metadata) {
		new InstanceDialog(fragmentManager, null, provider -> {
			Context context = GalleryInstance.getCallback(provider).getWindow().getContext();
			AlertDialog dialog = new AlertDialog.Builder(context)
					.setTitle(R.string.metadata)
					.setPositiveButton(android.R.string.ok, null)
					.create();
			SummaryLayout layout = new SummaryLayout(dialog);
			String videoFormat = metadata.get("video_format");
			String width = metadata.get("width");
			String height = metadata.get("height");
			String frameRate = metadata.get("frame_rate");
			String pixelFormat = metadata.get("pixel_format");
			String surfaceFormat = metadata.get("surface_format");
			String frameConversion = metadata.get("frame_conversion");
			String playerFfmpeg = metadata.get("player_ffmpeg");
			String playerLibavformat = metadata.get("player_libavformat");
			String playerBuild = metadata.get("player_build");
			String speedProcessing = metadata.get("speed_processing");
			String deviceAbis = metadata.get("device_abis");
			String audioFormat = metadata.get("audio_format");
			String channels = metadata.get("channels");
			String sampleRate = metadata.get("sample_rate");
			String encoder = metadata.get("encoder");
			String title = metadata.get("title");
			if (playerFfmpeg != null) {
				layout.add("FFmpeg", playerFfmpeg);
			}
			if (playerLibavformat != null) {
				layout.add("libavformat", playerLibavformat);
			}
			if (playerBuild != null) {
				layout.add("Build", playerBuild);
			}
			if (speedProcessing != null) {
				layout.add("Speed processing", speedProcessing);
			}
			if (deviceAbis != null) {
				layout.add("Device ABIs", deviceAbis);
			}
			layout.addDivider();
			if (videoFormat != null) {
				layout.add("Video", videoFormat);
			}
			if (width != null && height != null) {
				layout.add("Resolution", width + '×' + height);
			}
			if (frameRate != null) {
				layout.add("Frame rate", StringUtils.stripTrailingZeros(frameRate) + " FPS");
			}
			if (pixelFormat != null) {
				layout.add("Pixels", pixelFormat);
			}
			if (surfaceFormat != null) {
				layout.add("Surface", surfaceFormat);
			}
			if (frameConversion != null) {
				layout.add("Frame conversion", frameConversion);
			}
			layout.addDivider();
			if (audioFormat != null) {
				layout.add("Audio", audioFormat);
			}
			if (channels != null) {
				layout.add("Channels", channels);
			}
			if (sampleRate != null) {
				layout.add("Sample rate", sampleRate + " Hz");
			}
			layout.addDivider();
			if (encoder != null) {
				layout.add("Encoder", encoder);
			}
			if (!StringUtils.isEmptyOrWhitespace(title)) {
				layout.add("Title", title);
			}
			return dialog;
		});
	}

	private boolean controlsVisible = false;

	public void invalidateControlsVisibility() {
		boolean visible = initialized && instance.galleryInstance.callback.isSystemUiVisible();
		if (layoutConfiguration >= 0 && controlsVisible != visible) {
			controlsView.animate().cancel();
			if (visible) {
				controlsView.setVisibility(View.VISIBLE);
				controlsView.animate().alpha(1f).translationY(0f).setDuration(250).setListener(null)
						.setInterpolator(AnimationUtils.DECELERATE_INTERPOLATOR).start();
			} else {
				controlsView.animate().alpha(0f).translationY(controlsView.getHeight() -
						configurationView.getHeight()).setDuration(350)
						.setListener(new AnimationUtils.VisibilityListener(controlsView, View.GONE))
						.setInterpolator(AnimationUtils.ACCELERATE_DECELERATE_INTERPOLATOR).start();
			}
			controlsVisible = visible;
		}
	}

	private final VideoPlayer.Listener playerListener = new VideoPlayer.Listener() {
		@Override
		public void onComplete(VideoPlayer player) {
			switch (Preferences.getVideoCompletionMode()) {
				case NOTHING: {
					markPlaybackFinished();
					break;
				}
				case LOOP: {
					player.setPosition(0L);
					break;
				}
				default: {
					throw new IllegalStateException();
				}
			}
		}

		@Override
		public void onBusyStateChange(VideoPlayer player, boolean busy) {
			if (initialized) {
				PagerInstance.ViewHolder holder = instance.currentHolder;
				if (busy) {
					holder.progressBar.setIndeterminate(true);
				}
				holder.progressBar.setVisible(busy, false);
				if (!busy) {
					// The shared progress bar normally remains visible for at least 500 ms.
					// A seek is already debounced before it reaches this callback, so keeping
					// the indicator after the first frame is rendered only adds a false stall.
					holder.progressBar.cancelVisibilityTransient();
				}
			}
		}

		@Override
		public void onDurationChange(VideoPlayer player, long duration) {
			if (initialized && player == VideoUnit.this.player) {
				updateDuration(duration);
			}
		}

		@Override
		public void onDimensionChange(VideoPlayer player) {
			if (backgroundDrawable != null && player == VideoUnit.this.player) {
				backgroundDrawable.recycle();
				Point dimensions = player.getDimensions();
				backgroundDrawable.width = dimensions.x;
				backgroundDrawable.height = dimensions.y;
				PagerInstance.ViewHolder holder = instance.currentHolder;
				holder.photoView.resetScale();
				if (Preferences.isVideoZoomGesturesEnabled()) {
					View videoView = player.getVideoView(instance.galleryInstance.context);
					videoView.post(() -> captureAndApplyVideoTransform(holder, videoView));
				}
			}
		}
	};

	private void markPlaybackFinished() {
		finishedPlayback = true;
		wasPlaying = false;
		setPlaying(false, true);
		updatePlayState();
	}

	private void preserveFinishedPlaybackFrame() {
		if (backgroundDrawable == null || player == null) {
			return;
		}
		View videoView = player.getVideoView(instance.galleryInstance.context);
		if (videoView.getVisibility() != View.VISIBLE) {
			return;
		}
		Bitmap frame = player.getCurrentFrame();
		if (frame == null) {
			return;
		}
		Point dimensions = player.getDimensions();
		if (dimensions != null && dimensions.x > 0 && dimensions.y > 0
				&& (frame.getWidth() > dimensions.x || frame.getHeight() > dimensions.y)) {
			Bitmap scaledFrame = Bitmap.createScaledBitmap(frame, dimensions.x, dimensions.y, true);
			if (scaledFrame != frame) {
				frame.recycle();
				frame = scaledFrame;
			}
		}
		backgroundDrawable.setFrame(frame);
		videoView.setVisibility(View.GONE);
	}

	private void restoreVideoViewAfterFinishedPlayback() {
		if (backgroundDrawable != null && player != null) {
			backgroundDrawable.recycle();
			player.getVideoView(instance.galleryInstance.context).setVisibility(View.VISIBLE);
		}
	}

	public void showHideVideoView(boolean show) {
		if (initialized) {
			View videoView = player.getVideoView(instance.galleryInstance.context);
			if (show) {
				backgroundDrawable.recycle();
				videoView.setVisibility(View.VISIBLE);
			} else {
				backgroundDrawable.setFrame(player.getCurrentFrame());
				videoView.setVisibility(View.GONE);
			}
		}
	}

	public void handleSwipingContent(boolean swiping, boolean hideSurface) {
		if (initialized) {
			playPauseButton.setEnabled(!swiping);
			seekBar.setEnabled(!swiping);
			fullscreenButton.setEnabled(!swiping);
			if (swiping) {
				wasPlaying = player.isPlaying();
				setPlaying(false, true);
				if (hideSurface) {
					showHideVideoView(false);
				}
			} else {
				setPlaying(wasPlaying, true);
				if (hideSurface) {
					showHideVideoView(true);
				}
				updatePlayState();
			}
		} else if (player != null) {
			wasPlaying = !swiping;
			hideSurfaceOnInit = hideSurface && swiping;
		}
	}

	private class ReadVideoCallback implements ReadVideoTask.Callback, VideoPlayer.RangeCallback {
		private final VideoPlayer workPlayer;
		private final PagerInstance.ViewHolder holder;
		private final String chanName;
		private final Uri uri;

		private ReadVideoTask downloadTask;
		private ReadVideoTask rangeTask;
		private boolean allowRangeRequests;

		public ReadVideoCallback(VideoPlayer player, PagerInstance.ViewHolder holder, String chanName, Uri uri) {
			this.workPlayer = player;
			this.holder = holder;
			this.chanName = chanName;
			this.uri = uri;
			allowRangeRequests = !AdvancedPreferences.isSingleConnection(chanName);
			Chan chan = Chan.getPreferred(chanName, uri);
			downloadTask = new ReadVideoTask(this, chan, uri, 0);
			downloadTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
		}

		public void cancel() {
			if (downloadTask != null) {
				downloadTask.cancel();
				downloadTask = null;
			}
			if (rangeTask != null) {
				rangeTask.cancel();
				rangeTask = null;
			}
		}

		@Override
		public void onReadVideoInit(File partialFile) {
			if (workPlayer == player) {
				new Thread(() -> {
					boolean success;
					try {
						workPlayer.init(partialFile, ReadVideoCallback.this);
						success = true;
					} catch (VideoPlayer.InitializationException e) {
						e.printStackTrace();
						success = false;
					} catch (IOException e) {
						success = false;
					}
					boolean successFinal = success;
					ConcurrentUtils.HANDLER.post(() -> {
						if (workPlayer == player) {
							holder.progressBar.setVisible(false, false);
							if (successFinal) {
								initializePlayer();
								if (downloadTask == null) {
									seekBar.setSecondaryProgress(seekBar.getMax());
									holder.loadState = PagerInstance.LoadState.COMPLETE;
									updatePictureInPictureButton();
								}
								instance.galleryInstance.callback.invalidateOptionsMenu();
							} else {
								if (downloadTask != null) {
									if (!downloadTask.isError()) {
										downloadTask.cancel();
										downloadTask = null;
									} else {
										return;
									}
								}
								if (rangeTask != null) {
									rangeTask.cancel();
									rangeTask = null;
								}
								instance.callback.showError(holder, instance.galleryInstance.context
										.getString(R.string.playback_error));
							}
						}
					});
				}).start();
			}
		}

		@Override
		public void onReadVideoProgressUpdate(long progress, long progressMax) {
			if (workPlayer == player) {
				workPlayer.setDownloadRange(progress, progressMax);
				if (instance.currentHolder.mediaSummary.updateSize(progressMax)) {
					instance.galleryInstance.callback.updateTitle();
				}
				if (initialized) {
					int max = seekBar.getMax();
					if (max > 0 && progressMax > 0) {
						int newProgress = (int) (max * progress / progressMax);
						seekBar.setSecondaryProgress(newProgress);
					}
				}
			}
		}

		@Override
		public void onReadVideoRangeUpdate(long start, long end) {
			if (workPlayer == player) {
				workPlayer.setPartRange(start, end);
			}
		}

		@Override
		public void onReadVideoSuccess(boolean partial, File file) {
			if (workPlayer == player) {
				if (partial) {
					rangeTask = null;
				} else {
					downloadTask = null;
					long length = file.length();
					workPlayer.setDownloadRange(length, length);
					if (instance.currentHolder.mediaSummary.updateSize(length)) {
						instance.galleryInstance.callback.updateTitle();
					}
					if (initialized) {
						seekBar.setSecondaryProgress(seekBar.getMax());
						holder.loadState = PagerInstance.LoadState.COMPLETE;
						updatePictureInPictureButton();
						instance.galleryInstance.callback.invalidateOptionsMenu();
					}
				}
			}
		}

		@Override
		public void onReadVideoFail(boolean partial, ErrorItem errorItem, boolean disallowRangeRequests) {
			if (workPlayer == player) {
				if (partial) {
					rangeTask = null;
					if (disallowRangeRequests) {
						allowRangeRequests = false;
					}
				} else {
					holder.progressBar.setVisible(false, false);
					instance.callback.showError(holder, errorItem.toString());
				}
			}
		}

		@Override
		public void requestPartFromPosition(long start) {
			if (rangeTask != null) {
				rangeTask.cancel();
				rangeTask = null;
			}
			if (allowRangeRequests && start > 0) {
				Chan chan = Chan.getPreferred(chanName, uri);
				rangeTask = new ReadVideoTask(this, chan, uri, start);
				rangeTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
			}
		}
	}

	private static class BackgroundDrawable extends BaseDrawable {
		public int width;
		public int height;

		private Bitmap frame;
		private boolean draw = false;

		public void setFrame(Bitmap frame) {
			recycleInternal();
			this.frame = frame;
			draw = true;
			invalidateSelf();
		}

		public void recycle() {
			recycleInternal();
			if (draw) {
				draw = false;
				invalidateSelf();
			}
		}

		private void recycleInternal() {
			if (frame != null) {
				frame.recycle();
				frame = null;
			}
		}

		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

		@Override
		public void draw(@NonNull Canvas canvas) {
			if (draw) {
				Rect bounds = getBounds();
				paint.setColor(Color.BLACK);
				canvas.drawRect(bounds, paint);
				if (frame != null) {
					canvas.drawBitmap(frame, null, bounds, paint);
				}
			}
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSPARENT;
		}

		@Override
		public int getIntrinsicWidth() {
			return width;
		}

		@Override
		public int getIntrinsicHeight() {
			return height;
		}
	}
}
