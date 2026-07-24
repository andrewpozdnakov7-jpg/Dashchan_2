package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GestureSettingsFragment extends PreferenceFragment {
	private Preference<Void> areaPreference;

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		Preference<Boolean> enabledPreference = addCheck(true, Preferences.KEY_VIDEO_VOLUME_GESTURE,
				Preferences.DEFAULT_VIDEO_VOLUME_GESTURE, R.string.video_volume_gesture,
				R.string.video_volume_gesture__summary);
		areaPreference = addButton(getString(R.string.video_volume_gesture_area),
				preference -> formatAreaSummary());
		areaPreference.setEnabled(enabledPreference.getValue());
		areaPreference.setOnClickListener(preference -> GestureAreaDialog.show(getChildFragmentManager()));
		enabledPreference.setOnAfterChangeListener(preference -> {
			if (areaPreference != null) {
				areaPreference.setEnabled(preference.getValue());
			}
		});

		addCheck(true, Preferences.KEY_VIDEO_DOUBLE_TAP_SEEK, Preferences.DEFAULT_VIDEO_DOUBLE_TAP_SEEK,
				R.string.video_double_tap_seek, R.string.video_double_tap_seek__summary);
		List<String> seekIntervalValues = Arrays.asList("5", "10", "15", "30", "60");
		List<CharSequence> seekIntervalEntries = new ArrayList<>(seekIntervalValues.size());
		for (String value : seekIntervalValues) {
			seekIntervalEntries.add(getString(R.string.video_seek_seconds__format, Integer.parseInt(value)));
		}
		addList(Preferences.KEY_VIDEO_DOUBLE_TAP_SEEK_INTERVAL, seekIntervalValues,
				Preferences.DEFAULT_VIDEO_DOUBLE_TAP_SEEK_INTERVAL, R.string.video_double_tap_seek_interval,
				seekIntervalEntries);
		addDependency(Preferences.KEY_VIDEO_DOUBLE_TAP_SEEK_INTERVAL,
				Preferences.KEY_VIDEO_DOUBLE_TAP_SEEK, true);
	}

	private CharSequence formatAreaSummary() {
		int[] portraitInsets = Preferences.getVideoVolumeGestureInsets(false);
		int[] landscapeInsets = Preferences.getVideoVolumeGestureInsets(true);
		return getString(R.string.video_volume_gesture_area__summary_format,
				Preferences.getVideoVolumeGestureWidth(false), portraitInsets[0], portraitInsets[1],
				Preferences.getVideoVolumeGestureWidth(true), landscapeInsets[0], landscapeInsets[1]);
	}

	void onGestureAreaChanged() {
		if (areaPreference != null) {
			areaPreference.invalidate();
		}
	}

	@Override
	public void onDestroyView() {
		areaPreference = null;
		super.onDestroyView();
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.gesture_controls), null);
	}

	public static class GestureAreaDialog extends DialogFragment {
		private static final String TAG = GestureAreaDialog.class.getName();
		private static final String STATE_LANDSCAPE = "landscape";
		private static final String STATE_PORTRAIT_WIDTH = "portraitWidth";
		private static final String STATE_PORTRAIT_TOP = "portraitTop";
		private static final String STATE_PORTRAIT_BOTTOM = "portraitBottom";
		private static final String STATE_LANDSCAPE_WIDTH = "landscapeWidth";
		private static final String STATE_LANDSCAPE_TOP = "landscapeTop";
		private static final String STATE_LANDSCAPE_BOTTOM = "landscapeBottom";
		private static final int STEP = 5;

		private boolean landscape;
		private int portraitWidth;
		private int portraitTop;
		private int portraitBottom;
		private int landscapeWidth;
		private int landscapeTop;
		private int landscapeBottom;
		private boolean updatingControls;

		private FrameLayout previewContainer;
		private FrameLayout screenView;
		private View activeAreaView;
		private TextView widthLabel;
		private TextView topLabel;
		private TextView bottomLabel;
		private SeekBar widthSeekBar;
		private SeekBar topSeekBar;
		private SeekBar bottomSeekBar;

		public static void show(FragmentManager fragmentManager) {
			if (fragmentManager.findFragmentByTag(TAG) == null) {
				new GestureAreaDialog().show(fragmentManager, TAG);
			}
		}

		@Override
		@NonNull
		public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
			loadValues(savedInstanceState);
			Context context = requireContext();
			float density = ResourceUtils.obtainDensity(context);
			int padding = Math.round(20f * density);

			LinearLayout layout = new LinearLayout(context);
			layout.setOrientation(LinearLayout.VERTICAL);
			layout.setPadding(padding, Math.round(8f * density), padding, Math.round(4f * density));

			TextView hint = new TextView(context);
			ThemeEngine.applyStyle(hint);
			hint.setText(R.string.video_volume_gesture_area_preview_hint);
			hint.setTextColor(ThemeEngine.getTheme(context).meta);
			layout.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));

			RadioGroup orientationGroup = new RadioGroup(context);
			orientationGroup.setOrientation(LinearLayout.HORIZONTAL);
			RadioButton portraitButton = new RadioButton(context);
			ThemeEngine.applyStyle(portraitButton);
			portraitButton.setId(View.generateViewId());
			portraitButton.setText(R.string.gesture_orientation_portrait);
			RadioButton landscapeButton = new RadioButton(context);
			ThemeEngine.applyStyle(landscapeButton);
			landscapeButton.setId(View.generateViewId());
			landscapeButton.setText(R.string.gesture_orientation_landscape);
			orientationGroup.addView(portraitButton,
					new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
			orientationGroup.addView(landscapeButton,
					new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
			LinearLayout.LayoutParams orientationParams = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			orientationParams.topMargin = Math.round(8f * density);
			layout.addView(orientationGroup, orientationParams);

			previewContainer = new FrameLayout(context);
			LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, Math.round(220f * density));
			previewParams.topMargin = Math.round(4f * density);
			previewParams.bottomMargin = Math.round(8f * density);
			layout.addView(previewContainer, previewParams);

			screenView = new FrameLayout(context);
			GradientDrawable screenBackground = new GradientDrawable();
			screenBackground.setColor(0xff30343b);
			screenBackground.setCornerRadius(6f * density);
			screenBackground.setStroke(Math.max(1, Math.round(density)), 0xff8a8f98);
			screenView.setBackground(screenBackground);
			previewContainer.addView(screenView);

			activeAreaView = new View(context);
			GradientDrawable activeBackground = new GradientDrawable();
			int accent = ThemeEngine.getTheme(context).accent | 0xff000000;
			activeBackground.setColor((accent & 0x00ffffff) | 0x55000000);
			activeBackground.setStroke(Math.max(2, Math.round(2f * density)), accent);
			activeAreaView.setBackground(activeBackground);
			screenView.addView(activeAreaView);

			widthLabel = addControl(context, layout, Preferences.MIN_VIDEO_VOLUME_GESTURE_WIDTH,
					Preferences.MAX_VIDEO_VOLUME_GESTURE_WIDTH, density);
			widthSeekBar = (SeekBar) widthLabel.getTag();
			topLabel = addControl(context, layout, 0, Preferences.MAX_VIDEO_VOLUME_GESTURE_INSET, density);
			topSeekBar = (SeekBar) topLabel.getTag();
			bottomLabel = addControl(context, layout, 0, Preferences.MAX_VIDEO_VOLUME_GESTURE_INSET, density);
			bottomSeekBar = (SeekBar) bottomLabel.getTag();

			SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					if (!updatingControls) {
						updateValue(seekBar, progress * STEP + (seekBar == widthSeekBar
								? Preferences.MIN_VIDEO_VOLUME_GESTURE_WIDTH : 0));
					}
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {}
			};
			widthSeekBar.setOnSeekBarChangeListener(listener);
			topSeekBar.setOnSeekBarChangeListener(listener);
			bottomSeekBar.setOnSeekBarChangeListener(listener);

			orientationGroup.setOnCheckedChangeListener((group, checkedId) -> {
				landscape = checkedId == landscapeButton.getId();
				updateControls();
			});
			orientationGroup.check(landscape ? landscapeButton.getId() : portraitButton.getId());

			ScrollView scrollView = new ScrollView(context);
			scrollView.addView(layout, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));

			return new AlertDialog.Builder(context)
					.setTitle(R.string.video_volume_gesture_area)
					.setView(scrollView)
					.setNegativeButton(android.R.string.cancel, null)
					.setNeutralButton(R.string.restore_defaults, null)
					.setPositiveButton(android.R.string.ok, (dialog, which) -> saveValues())
					.create();
		}

		@Override
		public void onStart() {
			super.onStart();
			AlertDialog dialog = (AlertDialog) getDialog();
			if (dialog != null) {
				dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
					portraitWidth = Preferences.DEFAULT_VIDEO_VOLUME_GESTURE_PORTRAIT_WIDTH;
					portraitTop = Preferences.DEFAULT_VIDEO_VOLUME_GESTURE_PORTRAIT_TOP;
					portraitBottom = Preferences.DEFAULT_VIDEO_VOLUME_GESTURE_PORTRAIT_BOTTOM;
					landscapeWidth = Preferences.DEFAULT_VIDEO_VOLUME_GESTURE_LANDSCAPE_WIDTH;
					landscapeTop = Preferences.DEFAULT_VIDEO_VOLUME_GESTURE_LANDSCAPE_TOP;
					landscapeBottom = Preferences.DEFAULT_VIDEO_VOLUME_GESTURE_LANDSCAPE_BOTTOM;
					updateControls();
				});
			}
		}

		private void loadValues(Bundle state) {
			if (state != null) {
				landscape = state.getBoolean(STATE_LANDSCAPE);
				portraitWidth = state.getInt(STATE_PORTRAIT_WIDTH);
				portraitTop = state.getInt(STATE_PORTRAIT_TOP);
				portraitBottom = state.getInt(STATE_PORTRAIT_BOTTOM);
				landscapeWidth = state.getInt(STATE_LANDSCAPE_WIDTH);
				landscapeTop = state.getInt(STATE_LANDSCAPE_TOP);
				landscapeBottom = state.getInt(STATE_LANDSCAPE_BOTTOM);
			} else {
				landscape = getResources().getConfiguration().orientation
						== Configuration.ORIENTATION_LANDSCAPE;
				portraitWidth = Preferences.getVideoVolumeGestureWidth(false);
				int[] portraitInsets = Preferences.getVideoVolumeGestureInsets(false);
				portraitTop = portraitInsets[0];
				portraitBottom = portraitInsets[1];
				landscapeWidth = Preferences.getVideoVolumeGestureWidth(true);
				int[] landscapeInsets = Preferences.getVideoVolumeGestureInsets(true);
				landscapeTop = landscapeInsets[0];
				landscapeBottom = landscapeInsets[1];
			}
		}

		private TextView addControl(Context context, LinearLayout layout, int min, int max, float density) {
			TextView label = new TextView(context);
			ThemeEngine.applyStyle(label);
			LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			labelParams.topMargin = Math.round(4f * density);
			layout.addView(label, labelParams);

			SeekBar seekBar = new SeekBar(context);
			seekBar.setMax((max - min) / STEP);
			seekBar.setSaveEnabled(false);
			layout.addView(seekBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			label.setTag(seekBar);
			return label;
		}

		private void updateValue(SeekBar seekBar, int value) {
			if (landscape) {
				if (seekBar == widthSeekBar) {
					landscapeWidth = value;
				} else if (seekBar == topSeekBar) {
					landscapeTop = value;
					landscapeBottom = Math.min(landscapeBottom,
							100 - Preferences.MIN_VIDEO_VOLUME_GESTURE_HEIGHT - landscapeTop);
				} else {
					landscapeBottom = value;
					landscapeTop = Math.min(landscapeTop,
							100 - Preferences.MIN_VIDEO_VOLUME_GESTURE_HEIGHT - landscapeBottom);
				}
			} else {
				if (seekBar == widthSeekBar) {
					portraitWidth = value;
				} else if (seekBar == topSeekBar) {
					portraitTop = value;
					portraitBottom = Math.min(portraitBottom,
							100 - Preferences.MIN_VIDEO_VOLUME_GESTURE_HEIGHT - portraitTop);
				} else {
					portraitBottom = value;
					portraitTop = Math.min(portraitTop,
							100 - Preferences.MIN_VIDEO_VOLUME_GESTURE_HEIGHT - portraitBottom);
				}
			}
			updateControls();
		}

		private void updateControls() {
			if (widthSeekBar == null) {
				return;
			}
			int width = landscape ? landscapeWidth : portraitWidth;
			int top = landscape ? landscapeTop : portraitTop;
			int bottom = landscape ? landscapeBottom : portraitBottom;
			updatingControls = true;
			widthSeekBar.setProgress((width - Preferences.MIN_VIDEO_VOLUME_GESTURE_WIDTH) / STEP);
			topSeekBar.setProgress(top / STEP);
			bottomSeekBar.setProgress(bottom / STEP);
			updatingControls = false;
			widthLabel.setText(getString(R.string.video_volume_gesture_width__format, width));
			topLabel.setText(getString(R.string.video_volume_gesture_top__format, top));
			bottomLabel.setText(getString(R.string.video_volume_gesture_bottom__format, bottom));
			previewContainer.post(this::updatePreview);
		}

		private void updatePreview() {
			int availableWidth = previewContainer.getWidth();
			int availableHeight = previewContainer.getHeight();
			if (availableWidth <= 0 || availableHeight <= 0) {
				return;
			}
			int screenWidth;
			int screenHeight;
			if (landscape) {
				screenWidth = availableWidth;
				screenHeight = Math.round(screenWidth * 9f / 16f);
				if (screenHeight > availableHeight) {
					screenHeight = availableHeight;
					screenWidth = Math.round(screenHeight * 16f / 9f);
				}
			} else {
				screenHeight = availableHeight;
				screenWidth = Math.round(screenHeight * 9f / 16f);
			}
			FrameLayout.LayoutParams screenParams = new FrameLayout.LayoutParams(screenWidth, screenHeight);
			screenParams.gravity = Gravity.CENTER;
			screenView.setLayoutParams(screenParams);

			int width = landscape ? landscapeWidth : portraitWidth;
			int top = landscape ? landscapeTop : portraitTop;
			int bottom = landscape ? landscapeBottom : portraitBottom;
			FrameLayout.LayoutParams areaParams = new FrameLayout.LayoutParams(
					Math.max(1, Math.round(screenWidth * width / 100f)),
					Math.max(1, Math.round(screenHeight * (100 - top - bottom) / 100f)));
			areaParams.gravity = Gravity.TOP | Gravity.END;
			areaParams.topMargin = Math.round(screenHeight * top / 100f);
			activeAreaView.setLayoutParams(areaParams);
		}

		private void saveValues() {
			Preferences.setVideoVolumeGestureArea(false, portraitWidth, portraitTop, portraitBottom);
			Preferences.setVideoVolumeGestureArea(true, landscapeWidth, landscapeTop, landscapeBottom);
			if (getParentFragment() instanceof GestureSettingsFragment) {
				((GestureSettingsFragment) getParentFragment()).onGestureAreaChanged();
			}
		}

		@Override
		public void onSaveInstanceState(@NonNull Bundle outState) {
			super.onSaveInstanceState(outState);
			outState.putBoolean(STATE_LANDSCAPE, landscape);
			outState.putInt(STATE_PORTRAIT_WIDTH, portraitWidth);
			outState.putInt(STATE_PORTRAIT_TOP, portraitTop);
			outState.putInt(STATE_PORTRAIT_BOTTOM, portraitBottom);
			outState.putInt(STATE_LANDSCAPE_WIDTH, landscapeWidth);
			outState.putInt(STATE_LANDSCAPE_TOP, landscapeTop);
			outState.putInt(STATE_LANDSCAPE_BOTTOM, landscapeBottom);
		}
	}
}
