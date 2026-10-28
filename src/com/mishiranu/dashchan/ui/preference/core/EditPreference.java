package com.mishiranu.dashchan.ui.preference.core;

import com.mishiranu.dashchan.widget.InterfaceAppearance;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Pair;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import chan.util.StringUtils;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.FlagUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import com.mishiranu.dashchan.widget.ThemeEngine;

public class EditPreference extends DialogPreference<String> {
	public final CharSequence hint;
	public final int inputType;
	private CharSequence helperText;

	public EditPreference(Context context, String key, String defaultValue,
			CharSequence title, SummaryProvider<String> summaryProvider, CharSequence hint, int inputType) {
		super(context, key, defaultValue, title, summaryProvider);
		this.hint = hint;
		this.inputType = inputType;
	}

	public void setHelperText(CharSequence helperText) {
		this.helperText = helperText;
	}

	@Override
	protected void extract(SharedPreferences preferences) {
		setValue(preferences.getString(key, defaultValue));
	}

	@Override
	protected void persist(SharedPreferences preferences) {
		preferences.edit().put(key, getValue()).close();
	}

	@Override
	protected AlertDialog createDialog(Bundle savedInstanceState) {
		AlertDialog alertDialog = super.createDialog(savedInstanceState);
		alertDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
		return alertDialog;
	}

	@Override
	protected AlertDialog.Builder configureDialog(Bundle savedInstanceState, AlertDialog.Builder builder) {
		Pair<View, LinearLayout> pair = createDialogLayout(builder.getContext());
		SafePasteEditText editText = new SafePasteEditText(pair.second.getContext());
		editText.setId(android.R.id.edit);
		configureEdit(editText, hint, inputType, getValue());
		editText.requestFocus();
		addFieldLabel(pair.second, hint, editText);
		pair.second.addView(editText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		if (!StringUtils.isEmpty(helperText)) {
			TextView helper = new TextView(pair.second.getContext(), null, android.R.attr.textAppearanceSmall);
			ThemeEngine.applyStyle(helper);
			helper.setText(helperText);
			helper.setTextColor(ResourceUtils.getColor(helper.getContext(), android.R.attr.textColorSecondary));
			ViewUtils.setTextSizeScaled(helper, 12);
			helper.setSingleLine(false);
			helper.setMaxLines(Integer.MAX_VALUE);
			helper.setEllipsize(null);
			helper.setPaddingRelative(editText.getPaddingStart(),
					Math.round(4 * ResourceUtils.obtainDensity(helper)), editText.getPaddingEnd(), 0);
			InterfaceAppearance.standardCustomCaption(helper);
			pair.second.addView(helper, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		}
		return super.configureDialog(savedInstanceState, builder).setView(pair.first)
				.setPositiveButton(android.R.string.ok, (d, which) -> ConcurrentUtils.HANDLER
						.post(() -> setValue(editText.getText().toString())));
	}

	public static void configureEdit(EditText editText, CharSequence hint, int inputType, CharSequence text) {
		editText.setHint(hint);
		boolean visiblePassword = FlagUtils.get(inputType, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
		inputType = FlagUtils.set(inputType, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, false);
		editText.setInputType(inputType);
		if (visiblePassword) {
			ViewUtils.applyMonospaceTypeface(editText);
		}
		if (FlagUtils.get(inputType, InputType.TYPE_CLASS_NUMBER)) {
			editText.setFilters(new InputFilter[] {new InputFilter
					.LengthFilter(Integer.toString(Integer.MAX_VALUE).length() - 1)});
		}
		editText.setText(text);
		editText.setSelection(editText.getText().length());
		InterfaceAppearance.field(editText);
	}
}
