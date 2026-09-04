package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.fragment.app.FragmentManager;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.Locale;

public final class PlaybackSpeedDialog {
	private static final int SLOW_PROGRESS_MAX = 99;
	private static final int PROGRESS_MAX = 189;

	public interface Callback {
		void onPlaybackSpeedSelected(int playbackSpeed);
	}

	private PlaybackSpeedDialog() {}

	public static String formatPlaybackSpeed(int playbackSpeed) {
		if (playbackSpeed % 1000 == 0) {
			return String.format(Locale.US, "%dx", playbackSpeed / 1000);
		} else if (playbackSpeed % 100 == 0) {
			return String.format(Locale.US, "%.1fx", playbackSpeed / 1000f);
		}
		return String.format(Locale.US, "%.2fx", playbackSpeed / 1000f);
	}

	public static String formatPlaybackSpeedSummary(Context context, int playbackSpeed) {
		return context.getString(R.string.playback_speed_percent__format,
				playbackSpeed / 10, formatPlaybackSpeed(playbackSpeed));
	}

	private static int progressToPercent(int progress) {
		return progress <= SLOW_PROGRESS_MAX ? progress + 1
				: 100 + (progress - SLOW_PROGRESS_MAX) * 10;
	}

	private static int percentToProgress(int percent) {
		percent = Math.max(1, Math.min(percent, 1000));
		return percent <= 100 ? percent - 1
				: SLOW_PROGRESS_MAX + Math.round((percent - 100) / 10f);
	}

	public static void show(FragmentManager fragmentManager, int playbackSpeed, Callback callback) {
		new InstanceDialog(fragmentManager, PlaybackSpeedDialog.class.getName(), provider -> {
			Context context = provider.getContext();
			float density = ResourceUtils.obtainDensity(context);
			int horizontalPadding = Math.round(24f * density);
			int verticalPadding = Math.round(8f * density);
			LinearLayout layout = new LinearLayout(context);
			layout.setOrientation(LinearLayout.VERTICAL);
			layout.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

			TextView label = new TextView(context);
			ThemeEngine.applyStyle(label);
			label.setText(R.string.playback_speed_percent);
			layout.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));

			int initialPercent = Math.max(1, Math.min(playbackSpeed / 10, 1000));
			EditText input = new EditText(context);
			ThemeEngine.applyStyle(input);
			input.setSingleLine(true);
			input.setInputType(InputType.TYPE_CLASS_NUMBER);
			input.setText(Integer.toString(initialPercent));
			input.setSelectAllOnFocus(true);
			layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));

			SeekBar seekBar = new SeekBar(context);
			seekBar.setMax(PROGRESS_MAX);
			seekBar.setProgress(percentToProgress(initialPercent));
			seekBar.setContentDescription(context.getString(R.string.playback_speed));
			layout.addView(seekBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));

			TextView warning = new TextView(context);
			ThemeEngine.applyStyle(warning);
			warning.setText(R.string.playback_speed_extreme_warning);
			warning.setTextColor(ThemeEngine.getTheme(context).meta);
			layout.addView(warning, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));

			seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					if (fromUser) {
						input.setText(Integer.toString(progressToPercent(progress)));
						input.setSelection(input.length());
					}
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {}
			});
			input.addTextChangedListener(new TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
					try {
						int percent = Integer.parseInt(s.toString());
						if (percent >= 1 && percent <= 1000) {
							seekBar.setProgress(percentToProgress(percent));
							input.setError(null);
						}
					} catch (NumberFormatException e) {
						// The positive button performs final validation.
					}
				}

				@Override
				public void afterTextChanged(Editable s) {}
			});

			AlertDialog dialog = new AlertDialog.Builder(context)
					.setTitle(R.string.playback_speed)
					.setView(layout)
					.setNegativeButton(android.R.string.cancel, null)
					.setNeutralButton(R.string.restore_defaults, null)
					.setPositiveButton(android.R.string.ok, null)
					.create();
			dialog.setOnShowListener(d -> {
				dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
					input.setText("100");
					input.setSelection(input.length());
				});
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
					int percent;
					try {
						percent = Integer.parseInt(input.getText().toString());
					} catch (NumberFormatException e) {
						percent = 0;
					}
					if (percent < 1 || percent > 1000) {
						input.setError(context.getString(R.string.playback_speed_percent));
						return;
					}
					callback.onPlaybackSpeedSelected(percent * 10);
					dialog.dismiss();
				});
			});
			return dialog;
		});
	}
}
