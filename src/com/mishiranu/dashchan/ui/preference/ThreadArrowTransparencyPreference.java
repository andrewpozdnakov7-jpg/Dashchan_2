package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.preference.core.DialogPreference;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import com.mishiranu.dashchan.widget.ThemeEngine;

public class ThreadArrowTransparencyPreference extends DialogPreference<Integer> {
	private static final String STATE_INPUT = "input";
	private static final int MAX_VALUE = 100;
	private static final int SLIDER_STEP = 10;

	public ThreadArrowTransparencyPreference(Context context) {
		super(context, Preferences.KEY_THREAD_QUICK_NAVIGATION_TRANSPARENCY,
				Preferences.DEFAULT_THREAD_QUICK_NAVIGATION_TRANSPARENCY,
				context.getString(R.string.thread_quick_navigation_transparency), p -> p.getValue() + "%");
	}

	@Override
	protected void extract(SharedPreferences preferences) {
		setValue(Math.max(0, Math.min(MAX_VALUE, preferences.getInt(key, defaultValue))));
	}

	@Override
	protected void persist(SharedPreferences preferences) {
		preferences.edit().put(key, getValue()).close();
	}

	private static Integer parseValue(CharSequence text) {
		try {
			int value = Integer.parseInt(text.toString());
			return value >= 0 && value <= MAX_VALUE ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	protected AlertDialog createDialog(Bundle savedInstanceState) {
		Pair<View, LinearLayout> layout = createDialogLayout(context);
		LinearLayout row = new LinearLayout(context);
		row.setGravity(Gravity.CENTER_VERTICAL);
		SafePasteEditText input = new SafePasteEditText(context);
		input.setId(android.R.id.edit);
		ThemeEngine.applyStyle(input);
		input.setSingleLine(true);
		input.setInputType(InputType.TYPE_CLASS_NUMBER);
		input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(3)});
		input.setSelectAllOnFocus(true);
		input.setHint(R.string.thread_arrow_transparency_range);
		input.setContentDescription(title);
		input.setText(savedInstanceState != null ? savedInstanceState.getString(STATE_INPUT,
				Integer.toString(getValue())) : Integer.toString(getValue()));
		row.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		TextView unit = new TextView(context);
		ThemeEngine.applyStyle(unit);
		unit.setText("%");
		row.addView(unit, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		layout.second.addView(row, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		SeekBar seek = new SeekBar(context);
		seek.setMax(MAX_VALUE);
		seek.setKeyProgressIncrement(SLIDER_STEP);
		seek.setContentDescription(title);
		Integer initial = parseValue(input.getText());
		seek.setProgress(initial != null ? initial : getValue());
		layout.second.addView(seek, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		AlertDialog dialog = super.configureDialog(savedInstanceState, new AlertDialog.Builder(context))
				.setView(layout.first).setPositiveButton(android.R.string.ok, (d, which) -> {
					Integer value = parseValue(input.getText());
					if (value != null) ConcurrentUtils.HANDLER.post(() -> setValue(value));
				}).create();
		Runnable validate = () -> {
			Integer value = parseValue(input.getText());
			input.setError(value == null ? context.getString(R.string.thread_arrow_transparency_range) : null);
			if (value != null) seek.setProgress(value);
			if (dialog.isShowing()) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(value != null);
		};
		input.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
			@Override public void afterTextChanged(Editable s) { validate.run(); }
		});
		seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
				if (fromUser) {
					// Round only slider gestures: typed percentages retain their exact value.
					int value = Math.round(progress / (float) SLIDER_STEP) * SLIDER_STEP;
					bar.setProgress(value);
					input.setText(Integer.toString(value));
					input.setSelection(input.length());
				}
			}
			@Override public void onStartTrackingTouch(SeekBar bar) {}
			@Override public void onStopTrackingTouch(SeekBar bar) {}
		});
		dialog.setOnShowListener(d -> validate.run());
		return dialog;
	}

	@Override
	protected void saveState(AlertDialog dialog, Bundle outState) {
		super.saveState(dialog, outState);
		TextView input = dialog.findViewById(android.R.id.edit);
		outState.putString(STATE_INPUT, input.getText().toString());
	}
}
