package com.mishiranu.dashchan.ui.preference;

import com.mishiranu.dashchan.widget.MotionDialogBuilder;
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
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.DialogPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import com.mishiranu.dashchan.widget.ThemeEngine;

public class PostTextSizesFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		addScale(Preferences.KEY_SUBJECT_TEXT_SCALE, R.string.post_subject_text_size);
		addScale(Preferences.KEY_TEXT_SCALE, R.string.post_body_text_size);
		addScale(Preferences.KEY_METADATA_TEXT_SCALE, R.string.post_metadata_text_size);
		addHeader(R.string.post_text_size_preview);
		addPreference(new PreviewPreference(requireContext()), false);
		addButton(R.string.restore_defaults, 0).setOnClickListener(p -> {
			Preferences.resetPostTextScales();
			requireActivity().recreate();
		});
	}

	private void addScale(String key, int title) {
		ScalePreference preference = new ScalePreference(requireContext(), key, title);
		addDialogPreference(preference);
		preference.setOnAfterChangeListener(p -> requireActivity().recreate());
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.post_text_sizes), null);
	}

	private static class Preview extends LinearLayout {
		private final TextView subject, body, metadata;

		Preview(Context context) {
			super(context);
			setOrientation(VERTICAL);
			int padding = (int) (12f * ResourceUtils.obtainDensity(context));
			setPadding(padding, padding, padding, padding);
			ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
			metadata = addText(R.string.post_text_size_sample_metadata, theme.meta, padding);
			subject = addText(R.string.post_text_size_sample_subject, theme.post, padding);
			subject.setTypeface(ResourceUtils.TYPEFACE_LIGHT);
			body = addText(R.string.post_text_size_sample_body, theme.post, 0);
			update(null, 0);
		}

		private TextView addText(int text, int color, int bottomPadding) {
			TextView view = new TextView(getContext());
			ThemeEngine.applyStyle(view);
			view.setText(text);
			view.setTextColor(color);
			view.setPadding(0, 0, 0, bottomPadding);
			addView(view, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
			return view;
		}

		void update(String changedKey, int value) {
			apply(subject, 16, Preferences.KEY_SUBJECT_TEXT_SCALE, changedKey, value);
			apply(body, 12, Preferences.KEY_TEXT_SCALE, changedKey, value);
			apply(metadata, 12, Preferences.KEY_METADATA_TEXT_SCALE, changedKey, value);
		}

		private void apply(TextView view, int baseSp, String key, String changedKey, int value) {
			view.setTextSize(baseSp);
			ViewUtils.applyScaleSize((key.equals(changedKey) ? value : Preferences.getPostTextScalePercent(key))
					/ 100f, view);
		}
	}

	private static class PreviewPreference extends Preference<Void> {
		PreviewPreference(Context context) {
			super(context, null, null, null, null);
			setSelectable(false);
		}
		@Override public ViewType getViewType() { return ViewType.TEXT_SIZE_PREVIEW; }
		@Override protected void extract(SharedPreferences preferences) {}
		@Override protected void persist(SharedPreferences preferences) {}
		@Override public ViewHolder createViewHolder(ViewGroup parent) {
			return new ViewHolder(new Preview(parent.getContext()), null, null, null);
		}
		@Override public void bindViewHolder(ViewHolder holder) {
			super.bindViewHolder(holder);
			((Preview) holder.view).update(null, 0);
		}
	}

	private static class ScalePreference extends DialogPreference<Integer> {
		private static final String STATE_INPUT = "scale_input";

		ScalePreference(Context context, String key, int title) {
			super(context, key, Preferences.DEFAULT_TEXT_SCALE, context.getString(title), p -> p.getValue() + "%");
			setDescription(context.getString(R.string.large_text_layout_warning));
		}
		@Override protected void extract(SharedPreferences preferences) {
			setValue(Preferences.getPostTextScalePercent(key));
		}
		@Override protected void persist(SharedPreferences preferences) {
			Preferences.setPostTextScalePercent(key, getValue());
		}

		private static Integer parse(CharSequence text) {
			try {
				int value = Integer.parseInt(text.toString());
				return value >= Preferences.MIN_TEXT_SCALE && value <= Preferences.MAX_TEXT_SCALE ? value : null;
			} catch (NumberFormatException e) { return null; }
		}

		@Override protected AlertDialog createDialog(Bundle savedInstanceState) {
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
			input.setHint(R.string.post_text_scale_range);
			input.setContentDescription(title);
			input.setText(savedInstanceState != null ? savedInstanceState.getString(STATE_INPUT,
					Integer.toString(getValue())) : Integer.toString(getValue()));
			row.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			TextView unit = new TextView(context);
			ThemeEngine.applyStyle(unit);
			unit.setText("%");
			row.addView(unit);
			layout.second.addView(row);
			SeekBar seek = new SeekBar(context);
			seek.setMax(Preferences.MAX_TEXT_SCALE - Preferences.MIN_TEXT_SCALE);
			seek.setKeyProgressIncrement(Preferences.STEP_TEXT_SCALE);
			seek.setContentDescription(title);
			Integer initial = parse(input.getText());
			seek.setProgress((initial != null ? initial : getValue()) - Preferences.MIN_TEXT_SCALE);
			layout.second.addView(seek, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
			Preview preview = new Preview(context);
			preview.update(key, initial != null ? initial : getValue());
			layout.second.addView(preview);
			AlertDialog dialog = super.configureDialog(savedInstanceState, new MotionDialogBuilder(context))
					.setView(layout.first).setPositiveButton(android.R.string.ok, (d, which) -> {
						Integer value = parse(input.getText());
						if (value != null) ConcurrentUtils.HANDLER.post(() -> setValue(value));
					}).create();
			Runnable validate = () -> {
				Integer value = parse(input.getText());
				input.setError(value == null ? context.getString(R.string.post_text_scale_range) : null);
				if (value != null) {
					seek.setProgress(value - Preferences.MIN_TEXT_SCALE);
					preview.update(key, value);
				}
				if (dialog.isShowing()) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(value != null);
			};
			input.addTextChangedListener(new TextWatcher() {
				@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
				@Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
				@Override public void afterTextChanged(Editable s) { validate.run(); }
			});
			seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
					if (fromUser) {
						int value = Preferences.MIN_TEXT_SCALE + Math.round(progress / (float) Preferences.STEP_TEXT_SCALE)
								* Preferences.STEP_TEXT_SCALE;
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

		@Override protected void saveState(AlertDialog dialog, Bundle outState) {
			TextView input = dialog.findViewById(android.R.id.edit);
			outState.putString(STATE_INPUT, input.getText().toString());
		}
	}
}
