package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.preference.core.DialogPreference;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ThemeEngine;

public class RoundedDialogsRadiusPreference extends DialogPreference<Integer> {
	private static final String STATE_VALUE = "value";

	private EditText valueEdit;
	private SeekBar seekBar;
	private TextView preview;
	private int currentValue;
	private boolean updating;

	public RoundedDialogsRadiusPreference(Context context) {
		super(context, Preferences.KEY_ROUNDED_DIALOGS_RADIUS,
				Preferences.DEFAULT_ROUNDED_DIALOGS_RADIUS, context.getString(R.string.rounded_dialogs_radius),
				preference -> context.getString(R.string.rounded_dialogs_radius__summary_format,
						preference.getValue()));
	}

	@Override
	protected void extract(SharedPreferences preferences) {
		setValue(Math.max(Preferences.MIN_ROUNDED_DIALOGS_RADIUS,
				Math.min(preferences.getInt(key, defaultValue), Preferences.MAX_ROUNDED_DIALOGS_RADIUS)));
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

		FrameLayout previewContainer = new FrameLayout(context);
		previewContainer.setBackgroundColor(ThemeEngine.getTheme(context).window);
		int containerPadding = (int) (18f * density);
		previewContainer.setPadding(containerPadding, containerPadding, containerPadding, containerPadding);
		preview = new TextView(context);
		ThemeEngine.applyStyle(preview);
		preview.setGravity(Gravity.CENTER);
		preview.setText(R.string.rounded_dialogs_preview);
		preview.setTextColor(ThemeEngine.getTheme(context).post);
		preview.setElevation(6f * density);
		int previewPadding = (int) (16f * density);
		preview.setPadding(previewPadding, previewPadding, previewPadding, previewPadding);
		previewContainer.addView(preview, FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT);
		LinearLayout.LayoutParams previewLayoutParams = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, (int) (140f * density));
		previewLayoutParams.bottomMargin = (int) (16f * density);
		pair.second.addView(previewContainer, previewLayoutParams);

		LinearLayout valueLayout = new LinearLayout(context);
		valueLayout.setGravity(Gravity.CENTER_VERTICAL);
		valueEdit = new EditText(context);
		ThemeEngine.applyStyle(valueEdit);
		valueEdit.setSingleLine(true);
		valueEdit.setSelectAllOnFocus(true);
		valueEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
		valueEdit.setFilters(new InputFilter[] {new InputFilter.LengthFilter(2)});
		valueLayout.addView(valueEdit, new LinearLayout.LayoutParams(0,
				LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		TextView unitView = new TextView(context);
		ThemeEngine.applyStyle(unitView);
		unitView.setText(R.string.dp);
		int unitPadding = (int) (12f * density);
		unitView.setPadding(unitPadding, 0, 0, 0);
		valueLayout.addView(unitView, LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		pair.second.addView(valueLayout, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);

		seekBar = new SeekBar(context);
		seekBar.setMax(Preferences.MAX_ROUNDED_DIALOGS_RADIUS - Preferences.MIN_ROUNDED_DIALOGS_RADIUS);
		pair.second.addView(seekBar, LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);

		currentValue = savedInstanceState != null ? savedInstanceState.getInt(STATE_VALUE, getValue()) : getValue();
		updateValue(currentValue, true);
		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser && !updating) {
					updateValue(Preferences.MIN_ROUNDED_DIALOGS_RADIUS + progress, true);
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
						updateValue(value, value < Preferences.MIN_ROUNDED_DIALOGS_RADIUS
								|| value > Preferences.MAX_ROUNDED_DIALOGS_RADIUS);
					} catch (NumberFormatException e) {
						// Keep the last valid value while the user edits the field.
					}
				}
			}
		});
		return super.configureDialog(savedInstanceState, builder).setView(pair.first)
				.setPositiveButton(android.R.string.ok, (dialog, which) ->
						ConcurrentUtils.HANDLER.post(() -> setValue(currentValue)));
	}

	private void updateValue(int value, boolean updateText) {
		currentValue = Math.max(Preferences.MIN_ROUNDED_DIALOGS_RADIUS,
				Math.min(value, Preferences.MAX_ROUNDED_DIALOGS_RADIUS));
		updating = true;
		if (updateText && valueEdit != null) {
			valueEdit.setText(Integer.toString(currentValue));
			valueEdit.setSelection(valueEdit.length());
		}
		if (seekBar != null) {
			seekBar.setProgress(currentValue - Preferences.MIN_ROUNDED_DIALOGS_RADIUS);
		}
		updating = false;
		if (preview != null) {
			GradientDrawable background = new GradientDrawable();
			background.setColor(ThemeEngine.getTheme(preview.getContext()).card);
			background.setCornerRadius(currentValue * ResourceUtils.obtainDensity(preview));
			preview.setBackground(background);
			preview.setClipToOutline(currentValue > 0);
		}
	}

	@Override
	protected void saveState(AlertDialog dialog, Bundle outState) {
		super.saveState(dialog, outState);
		outState.putInt(STATE_VALUE, currentValue);
	}

	@Override
	protected void stopDialog(AlertDialog dialog) {
		super.stopDialog(dialog);
		valueEdit = null;
		seekBar = null;
		preview = null;
	}
}
