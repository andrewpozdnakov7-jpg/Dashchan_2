package com.mishiranu.dashchan.ui;

import com.mishiranu.dashchan.widget.InterfaceAppearance;

import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.service.AudioPlayerService;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;

public class AudioPlayerDialog extends DialogFragment {
	private TextView textView;
	private SeekBar seekBar;
	private ImageButton button;
	private TextView elapsedView, durationView;
	private LinearLayout timeRow;
	private boolean appearanceEnabled;

	private boolean tracking = false;
	private boolean shouldCancel = false;

	private final AudioPlayerService.Callback callback = new AudioPlayerService.Callback() {
		@Override
		public void onTogglePlayback() {
			updatePlayState();
		}

		@Override
		public void onCancel() {
			handleCancel();
		}
	};

	private AudioPlayerService.Binder audioPlayerBinder;
	private final ServiceConnection audioPlayerConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName componentName, IBinder binder) {
			audioPlayerBinder = (AudioPlayerService.Binder) binder;
			audioPlayerBinder.registerCallback(callback);
			if (audioPlayerBinder.isRunning()) {
				seekBar.removeCallbacks(seekBarUpdate);
				textView.setText(audioPlayerBinder.getFileName());
				seekBar.setMax(audioPlayerBinder.getDuration());
				updateTime(audioPlayerBinder.getPosition());
				updatePlayState();
				seekBarUpdate.run();
			} else {
				handleCancel();
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			if (audioPlayerBinder != null) {
				audioPlayerBinder.unregisterCallback(callback);
				audioPlayerBinder = null;
			}
		}
	};

	private final Runnable seekBarUpdate = new Runnable() {
		@Override
		public void run() {
			if (audioPlayerBinder != null) {
				if (!tracking) {
					seekBar.setProgress(audioPlayerBinder.getPosition());
					updateTime(audioPlayerBinder.getPosition());
				}
				seekBar.postDelayed(this, 500);
			}
		}
	};

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		Context context = requireContext();
		float density = ResourceUtils.obtainDensity(this);
		LinearLayout linearLayout = new LinearLayout(context);
		linearLayout.setOrientation(LinearLayout.VERTICAL);
		int padding = getResources().getDimensionPixelSize(R.dimen.dialog_padding_view);
		linearLayout.setPadding(padding, padding, padding, (int) (8f * density));
		textView = new TextView(context, null, android.R.attr.textAppearanceListItem);
		ThemeEngine.applyStyle(textView);
		linearLayout.addView(textView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		textView.setPadding(0, 0, 0, 0);
		textView.setEllipsize(TextUtils.TruncateAt.END);
		textView.setSingleLine(true);
		InterfaceAppearance.audioTitle(textView);
		LinearLayout horizontal = new LinearLayout(context);
		horizontal.setOrientation(LinearLayout.HORIZONTAL);
		horizontal.setGravity(Gravity.CENTER_VERTICAL);
		horizontal.setPadding(0, (int) (16f * density), 0, 0);
		linearLayout.addView(horizontal, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		tracking = false;
		seekBar = new SeekBar(context);
		ThemeEngine.applyStyle(seekBar);
		horizontal.addView(seekBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		seekBar.setPadding((int) (8f * density), 0, (int) (16f * density), 0);
		InterfaceAppearance.slider(seekBar);
		seekBar.setContentDescription(getString(R.string.audio_playback));
		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (audioPlayerBinder != null && fromUser) {
					updateTime(progress);
					audioPlayerBinder.seekTo(progress);
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
				tracking = true;
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				tracking = false;
			}
		});
		button = new ImageButton(context);
		horizontal.addView(button, (int) (48f * density), (int) (48f * density));
		button.setImageTintList(ResourceUtils.getColorStateList(button.getContext(),
				android.R.attr.textColorPrimary));
		button.setBackgroundResource(ResourceUtils.getResourceId(context,
				android.R.attr.listChoiceBackgroundIndicator, 0));
		InterfaceAppearance.icon(button);
		updatePlayState();
		button.setOnClickListener(v -> {
			if (audioPlayerBinder != null) {
				audioPlayerBinder.togglePlayback();
			}
		});
		timeRow = new LinearLayout(context); timeRow.setOrientation(LinearLayout.HORIZONTAL);
		elapsedView = new TextView(context); durationView = new TextView(context);
		ThemeEngine.applyStyle(elapsedView); ThemeEngine.applyStyle(durationView);
		InterfaceAppearance.secondary(elapsedView); InterfaceAppearance.secondary(durationView);
		elapsedView.setFontFeatureSettings("tnum"); durationView.setFontFeatureSettings("tnum");
		durationView.setGravity(Gravity.END);
		timeRow.setPadding((int) (8f * density), 0, (int) (8f * density), 0);
		timeRow.addView(elapsedView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		timeRow.addView(durationView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		linearLayout.addView(timeRow); updateTime(0);
		AlertDialog dialog = new MotionDialogBuilder(context)
				.setView(linearLayout)
				.setPositiveButton(R.string.stop, (d, w) -> {
					if (audioPlayerBinder != null) {
						audioPlayerBinder.stop();
					}
				})
				.create();
		dialog.setVolumeControlStream(AudioManager.STREAM_MUSIC);
		requireContext().bindService(new Intent(requireContext(), AudioPlayerService.class),
				audioPlayerConnection, Context.BIND_AUTO_CREATE);
		return dialog;
	}

	@Override
	public void onDestroyView() {
		com.mishiranu.dashchan.widget.ElementMotion.finish(button);
		super.onDestroyView();

		if (audioPlayerBinder != null) {
			audioPlayerBinder.unregisterCallback(callback);
			audioPlayerBinder = null;
			requireContext().unbindService(audioPlayerConnection);
		}
		if (seekBar != null) {
			seekBar.removeCallbacks(seekBarUpdate);
		}
		textView = null;
		elapsedView = null; durationView = null; timeRow = null;
		seekBar = null;
		button = null;
	}

	@Override
	public void onResume() {
		super.onResume();

		if (shouldCancel) {
			shouldCancel = false;
			handleCancel();
		}
	}

	private void handleCancel() {
		if (isResumed()) {
			dismiss();
		} else {
			shouldCancel = false;
		}
	}

	private void updateTime(int position) {
		if (timeRow == null) return;
		boolean enabled = InterfaceAppearance.isEnabled(timeRow.getContext());
		if (appearanceEnabled != enabled) {
			appearanceEnabled = enabled;
			Dialog dialog = getDialog();
			InterfaceAppearance.refreshTree(dialog != null && dialog.getWindow() != null
					? dialog.getWindow().getDecorView() : (android.view.View) timeRow.getParent());
		}
		timeRow.setVisibility(enabled ? android.view.View.VISIBLE : android.view.View.GONE);
		if (!enabled) return;
		elapsedView.setText(android.text.format.DateUtils.formatElapsedTime(Math.max(0, position) / 1000));
		int duration = audioPlayerBinder != null ? audioPlayerBinder.getDuration() : 0;
		durationView.setText(android.text.format.DateUtils.formatElapsedTime(Math.max(0, duration) / 1000));
	}

	private void updatePlayState() {
		boolean playing = audioPlayerBinder != null && audioPlayerBinder.isPlaying();
		int resource = ResourceUtils.getResourceId(requireContext(),
				playing ? R.attr.iconButtonPause : R.attr.iconButtonPlay, 0);
		com.mishiranu.dashchan.widget.ElementMotion.icon(button, resource);
		button.setContentDescription(getString(playing ? R.string.pause : R.string.play));
	}
}
