package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.VolumeGestureUtils;
import com.mishiranu.dashchan.ui.preference.core.DialogPreference;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ThemeEngine;

public class VolumeGestureSensitivityPreference extends DialogPreference<Integer> {
	private static final String STATE_VALUE = "value";
	private static final String STATE_TEST_VOLUME = "testVolume";

	private EditText valueEdit;
	private SeekBar seekBar;
	private GestureTestView testView;
	private int currentValue;
	private boolean updating;

	public VolumeGestureSensitivityPreference(Context context) {
		super(context, Preferences.KEY_VIDEO_VOLUME_GESTURE_SENSITIVITY,
				Preferences.DEFAULT_VIDEO_VOLUME_GESTURE_SENSITIVITY,
				context.getString(R.string.video_volume_gesture_sensitivity), preference -> context.getString(
						R.string.video_volume_gesture_sensitivity__summary_format, preference.getValue()));
		setNeutralButton(context.getString(R.string.restore_defaults), () -> {
			updateValue(Preferences.DEFAULT_VIDEO_VOLUME_GESTURE_SENSITIVITY, true);
			if (testView != null) {
				testView.resetVolume();
			}
		});
	}

	@Override
	protected void extract(SharedPreferences preferences) {
		setValue(clamp(preferences.getInt(key, defaultValue)));
	}

	@Override
	protected void persist(SharedPreferences preferences) {
		preferences.edit().put(key, getValue()).close();
	}

	@Override
	protected AlertDialog.Builder configureDialog(Bundle savedInstanceState, AlertDialog.Builder builder) {
		Context context = builder.getContext();
		float density = ResourceUtils.obtainDensity(context);
		Pair<View, LinearLayout> pair = createDialogLayout(context);

		TextView hintView = new TextView(context);
		ThemeEngine.applyStyle(hintView);
		hintView.setText(R.string.video_volume_gesture_test_hint);
		hintView.setTextColor(ThemeEngine.getTheme(context).meta);
		pair.second.addView(hintView, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);

		testView = new GestureTestView(context);
		LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, Math.round(240f * density));
		testParams.topMargin = Math.round(12f * density);
		testParams.bottomMargin = Math.round(12f * density);
		pair.second.addView(testView, testParams);
		Button resetTestButton = new Button(context);
		ThemeEngine.applyStyle(resetTestButton);
		resetTestButton.setText(R.string.video_volume_gesture_test_reset);
		resetTestButton.setOnClickListener(view -> testView.resetVolume());
		LinearLayout.LayoutParams resetTestParams = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		resetTestParams.gravity = Gravity.CENTER_HORIZONTAL;
		resetTestParams.bottomMargin = Math.round(8f * density);
		pair.second.addView(resetTestButton, resetTestParams);

		LinearLayout valueLayout = new LinearLayout(context);
		valueLayout.setGravity(Gravity.CENTER_VERTICAL);
		valueEdit = new EditText(context);
		ThemeEngine.applyStyle(valueEdit);
		valueEdit.setSingleLine(true);
		valueEdit.setSelectAllOnFocus(true);
		valueEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
		valueEdit.setFilters(new InputFilter[] {new InputFilter.LengthFilter(3)});
		valueEdit.setHint(context.getString(R.string.video_volume_gesture_sensitivity_range__format,
				Preferences.MIN_VIDEO_VOLUME_GESTURE_SENSITIVITY,
				Preferences.MAX_VIDEO_VOLUME_GESTURE_SENSITIVITY));
		valueLayout.addView(valueEdit, new LinearLayout.LayoutParams(0,
				LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		TextView unitView = new TextView(context);
		ThemeEngine.applyStyle(unitView);
		unitView.setText("%");
		unitView.setPadding(Math.round(12f * density), 0, 0, 0);
		valueLayout.addView(unitView, LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		pair.second.addView(valueLayout, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);

		seekBar = new SeekBar(context);
		seekBar.setMax(Preferences.MAX_VIDEO_VOLUME_GESTURE_SENSITIVITY
				- Preferences.MIN_VIDEO_VOLUME_GESTURE_SENSITIVITY);
		pair.second.addView(seekBar, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);

		currentValue = savedInstanceState != null ? savedInstanceState.getInt(STATE_VALUE, getValue()) : getValue();
		updateValue(currentValue, true);
		if (savedInstanceState != null) {
			testView.setVolume(savedInstanceState.getInt(STATE_TEST_VOLUME, 50));
		}
		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser && !updating) {
					updateValue(Preferences.MIN_VIDEO_VOLUME_GESTURE_SENSITIVITY + progress, true);
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {}
		});
		valueEdit.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {}

			@Override
			public void afterTextChanged(Editable editable) {
				if (!updating && editable.length() > 0) {
					try {
						int value = Integer.parseInt(editable.toString());
						boolean outOfRange = value < Preferences.MIN_VIDEO_VOLUME_GESTURE_SENSITIVITY
								|| value > Preferences.MAX_VIDEO_VOLUME_GESTURE_SENSITIVITY;
						valueEdit.setError(outOfRange ? context.getString(
								R.string.video_volume_gesture_sensitivity_range__format,
								Preferences.MIN_VIDEO_VOLUME_GESTURE_SENSITIVITY,
								Preferences.MAX_VIDEO_VOLUME_GESTURE_SENSITIVITY) : null);
						updateValue(value, false);
					} catch (NumberFormatException e) {
						// Keep the last valid value while the user edits the field.
					}
				}
			}
		});
		return super.configureDialog(savedInstanceState, builder)
				.setTitle(R.string.video_volume_gesture_configure_and_test).setView(pair.first)
				.setPositiveButton(android.R.string.ok, (dialog, which) ->
						ConcurrentUtils.HANDLER.post(() -> setValue(currentValue)));
	}

	private int clamp(int value) {
		return Math.max(Preferences.MIN_VIDEO_VOLUME_GESTURE_SENSITIVITY,
				Math.min(value, Preferences.MAX_VIDEO_VOLUME_GESTURE_SENSITIVITY));
	}

	private void updateValue(int value, boolean updateText) {
		currentValue = clamp(value);
		updating = true;
		if (updateText && valueEdit != null) {
			valueEdit.setText(Integer.toString(currentValue));
			valueEdit.setSelection(valueEdit.length());
			valueEdit.setError(null);
		}
		if (seekBar != null) {
			seekBar.setProgress(currentValue - Preferences.MIN_VIDEO_VOLUME_GESTURE_SENSITIVITY);
		}
		updating = false;
		if (testView != null) {
			testView.setSensitivity(currentValue);
		}
	}

	@Override
	protected void saveState(AlertDialog dialog, Bundle outState) {
		super.saveState(dialog, outState);
		outState.putInt(STATE_VALUE, currentValue);
		if (testView != null) {
			outState.putInt(STATE_TEST_VOLUME, testView.getVolume());
		}
	}

	@Override
	protected void stopDialog(AlertDialog dialog) {
		super.stopDialog(dialog);
		valueEdit = null;
		seekBar = null;
		testView = null;
	}

	private static class GestureTestView extends FrameLayout {
		private final View activeAreaView;
		private final TextView volumeView;
		private final int activeWidthPercent;
		private final int activeTopPercent;
		private final int activeBottomPercent;
		private final boolean leftEdge;
		private int sensitivity = Preferences.DEFAULT_VIDEO_VOLUME_GESTURE_SENSITIVITY;
		private int volume = 50;
		private int startVolume;
		private float startY;
		private boolean tracking;

		public GestureTestView(Context context) {
			super(context);
			float density = ResourceUtils.obtainDensity(context);
			boolean landscape = context.getResources().getConfiguration().orientation
					== Configuration.ORIENTATION_LANDSCAPE;
			activeWidthPercent = Preferences.getVideoVolumeGestureWidth(landscape);
			leftEdge = Preferences.isVideoRightHandControls();
			int[] insets = Preferences.getVideoVolumeGestureInsets(landscape);
			activeTopPercent = insets[0];
			activeBottomPercent = insets[1];
			GradientDrawable background = new GradientDrawable();
			background.setColor(ThemeEngine.getTheme(context).window);
			background.setCornerRadius(6f * density);
			background.setStroke(Math.max(1, Math.round(density)), ThemeEngine.getTheme(context).meta);
			setBackground(background);
			setClipToOutline(true);
			setClickable(true);
			setContentDescription(context.getString(R.string.video_volume_gesture_test_area));

			activeAreaView = new View(context);
			GradientDrawable activeBackground = new GradientDrawable();
			int accent = ThemeEngine.getTheme(context).accent | 0xff000000;
			activeBackground.setColor((accent & 0x00ffffff) | 0x55000000);
			activeAreaView.setBackground(activeBackground);
			addView(activeAreaView, new FrameLayout.LayoutParams(1,
					FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP | (leftEdge
							? Gravity.START : Gravity.END)));

			volumeView = new TextView(context);
			ThemeEngine.applyStyle(volumeView);
			volumeView.setTextColor(Color.WHITE);
			volumeView.setGravity(Gravity.CENTER);
			volumeView.setPadding(Math.round(14f * density), Math.round(8f * density),
					Math.round(14f * density), Math.round(8f * density));
			GradientDrawable labelBackground = new GradientDrawable();
			labelBackground.setColor(0xbb202020);
			labelBackground.setCornerRadius(4f * density);
			volumeView.setBackground(labelBackground);
			FrameLayout.LayoutParams volumeParams = new FrameLayout.LayoutParams(
					FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
			addView(volumeView, volumeParams);
			updateVolumeText();
		}

		@Override
		protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
			super.onSizeChanged(width, height, oldWidth, oldHeight);
			FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) activeAreaView.getLayoutParams();
			params.width = Math.max(1, width * activeWidthPercent / 100);
			params.height = Math.max(1, height * (100 - activeTopPercent - activeBottomPercent) / 100);
			params.topMargin = height * activeTopPercent / 100;
			activeAreaView.setLayoutParams(params);
		}

		@Override
		public boolean onTouchEvent(MotionEvent event) {
			switch (event.getActionMasked()) {
				case MotionEvent.ACTION_DOWN: {
					if ((leftEdge && event.getX() > getWidth() * activeWidthPercent / 100f)
							|| (!leftEdge && event.getX() < getWidth() * (100 - activeWidthPercent) / 100f)
							|| event.getY() < getHeight() * activeTopPercent / 100f
							|| event.getY() > getHeight() * (100 - activeBottomPercent) / 100f) {
						return false;
					}
					tracking = true;
					startY = event.getY();
					startVolume = volume;
					getParent().requestDisallowInterceptTouchEvent(true);
					return true;
				}
				case MotionEvent.ACTION_MOVE: {
					if (tracking) {
						setVolume(VolumeGestureUtils.calculateVolume(startVolume, 100,
								(startY - event.getY()) / Math.max(1f, getHeight()), sensitivity));
						return true;
					}
					break;
				}
				case MotionEvent.ACTION_UP: {
					if (tracking) {
						tracking = false;
						getParent().requestDisallowInterceptTouchEvent(false);
						performClick();
						return true;
					}
					break;
				}
				case MotionEvent.ACTION_CANCEL: {
					tracking = false;
					getParent().requestDisallowInterceptTouchEvent(false);
					break;
				}
			}
			return super.onTouchEvent(event);
		}

		@Override
		public boolean performClick() {
			super.performClick();
			return true;
		}

		public void setSensitivity(int sensitivity) {
			this.sensitivity = sensitivity;
		}

		public int getVolume() {
			return volume;
		}

		public void setVolume(int volume) {
			this.volume = Math.max(0, Math.min(100, volume));
			updateVolumeText();
		}

		public void resetVolume() {
			setVolume(50);
		}

		private void updateVolumeText() {
			volumeView.setText(getContext().getString(R.string.video_volume__format, volume));
		}
	}
}
