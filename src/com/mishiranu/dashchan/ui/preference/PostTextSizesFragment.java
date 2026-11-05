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
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.SettingsTextAppearance;
import com.mishiranu.dashchan.widget.DrawerStyle;
import com.mishiranu.dashchan.widget.ViewFactory;
import java.util.function.IntConsumer;

public class PostTextSizesFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		addScale(Preferences.KEY_SUBJECT_TEXT_SCALE, R.string.post_subject_text_size);
		addScale(Preferences.KEY_TEXT_SCALE, R.string.post_body_text_size);
		addScale(Preferences.KEY_METADATA_TEXT_SCALE, R.string.post_metadata_text_size);
		addScale(Preferences.KEY_SETTINGS_TEXT_SCALE, R.string.settings_text_size);
		addScale(Preferences.KEY_DRAWER_TEXT_SCALE, R.string.drawer_text_size);
		addHeader(R.string.post_text_size_preview);
		addPreference(new PreviewPreference(requireContext()), false);
		addButton(R.string.restore_defaults, 0).setOnClickListener(p -> {
			Preferences.resetPostTextScales();
			Preferences.setSettingsTextScalePercent(Preferences.DEFAULT_TEXT_SCALE);
			Preferences.setDrawerTextScalePercent(Preferences.DEFAULT_TEXT_SCALE);
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

	private static class SettingsPreview extends LinearLayout {
		private final ViewFactory.TwoLinesViewHolder row;

		SettingsPreview(Context context) {
			super(context);
			setOrientation(VERTICAL);
			row = ViewFactory.makeTwoLinesListItem(this, ViewFactory.FEATURE_MULTILINE_TITLE);
			row.text1.setText(R.string.settings_text_size_sample_title);
			row.text2.setText(R.string.settings_text_size_sample_summary);
			addView(row.view);
			update(Preferences.getSettingsTextScalePercent());
		}

		void update(int percent) {
			SettingsTextAppearance.restore(row.text1, row.text2);
			InterfaceAppearance.settingsRow(row.view, row.text1, row.text2, row.widgetFrame, false);
			SettingsTextAppearance.apply(percent, row.text1, row.text2);
		}
	}

	private static class DrawerPreview extends LinearLayout {
		private final TextView group, item;
		DrawerPreview(Context context) {
			super(context); setOrientation(VERTICAL);
			int padding = Math.round(12 * ResourceUtils.obtainDensity(context));
			setPadding(padding, padding, padding, padding);
			setBackgroundColor(DrawerStyle.surface(context));
			group = new TextView(context); item = new TextView(context);
			ThemeEngine.applyStyle(group); ThemeEngine.applyStyle(item);
			group.setText(R.string.favorite_threads); item.setText(R.string.history);
			addView(group, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
			addView(item, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
			update(Preferences.getDrawerTextScalePercent());
		}
		void update(int percent) {
			if (Preferences.isNewInterfaceMotionEnabled()) {
				DrawerStyle style = new DrawerStyle(getContext(), percent);
				style.apply(group, DrawerStyle.Role.GROUP); style.apply(item, DrawerStyle.Role.MENU);
			} else {
				int color = ResourceUtils.getColor(getContext(), android.R.attr.textColorPrimary);
				group.setTextColor(color & 0x5effffff); item.setTextColor(color & 0xddffffff);
				for (TextView view : new TextView[]{group, item}) {
					ViewUtils.setTextSizeScaled(view, 14); view.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);
					DrawerStyle.scaleText(view, percent);
				}
			}
		}
	}

	private static class FullPreview extends LinearLayout {
		private final Preview posts;
		private final SettingsPreview settings;
		private final DrawerPreview drawer;

		FullPreview(Context context) {
			super(context);
			setOrientation(VERTICAL);
			posts = new Preview(context);
			settings = new SettingsPreview(context);
			drawer = new DrawerPreview(context);
			addView(posts);
			addView(settings);
			addView(drawer);
		}

		void update() {
			posts.update(null, 0);
			settings.update(Preferences.getSettingsTextScalePercent());
			drawer.update(Preferences.getDrawerTextScalePercent());
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
			return new ViewHolder(new FullPreview(parent.getContext()), null, null, null);
		}
		@Override public void bindViewHolder(ViewHolder holder) {
			super.bindViewHolder(holder);
			((FullPreview) holder.view).update();
		}
	}

	private static class ScalePreference extends DialogPreference<Integer> {
		private static final String STATE_INPUT = "scale_input";

		ScalePreference(Context context, String key, int title) {
			super(context, key, Preferences.DEFAULT_TEXT_SCALE, context.getString(title), p -> p.getValue() + "%");
			setDescription(context.getString(R.string.large_text_layout_warning));
		}
		@Override protected void extract(SharedPreferences preferences) {
			setValue(Preferences.KEY_SETTINGS_TEXT_SCALE.equals(key) ? Preferences.getSettingsTextScalePercent()
					: Preferences.KEY_DRAWER_TEXT_SCALE.equals(key) ? Preferences.getDrawerTextScalePercent()
					: Preferences.getPostTextScalePercent(key));
		}
		@Override protected void persist(SharedPreferences preferences) {
			if (Preferences.KEY_SETTINGS_TEXT_SCALE.equals(key)) Preferences.setSettingsTextScalePercent(getValue());
			else if (Preferences.KEY_DRAWER_TEXT_SCALE.equals(key)) Preferences.setDrawerTextScalePercent(getValue());
			else Preferences.setPostTextScalePercent(key, getValue());
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
			View preview;
			IntConsumer updatePreview;
			if (Preferences.KEY_SETTINGS_TEXT_SCALE.equals(key)) {
				SettingsPreview settings = new SettingsPreview(context);
				preview = settings;
				updatePreview = settings::update;
			} else if (Preferences.KEY_DRAWER_TEXT_SCALE.equals(key)) {
				DrawerPreview drawer = new DrawerPreview(context);
				preview = drawer;
				updatePreview = drawer::update;
			} else {
				Preview posts = new Preview(context);
				preview = posts;
				updatePreview = value -> posts.update(key, value);
			}
			updatePreview.accept(initial != null ? initial : getValue());
			layout.second.addView(preview);
			com.mishiranu.dashchan.widget.InterfaceAppearance.standardCustomContent(layout.second, preview);
			com.mishiranu.dashchan.widget.InterfaceAppearance.standardCustomInsets(layout.second);
			AlertDialog dialog = super.configureDialog(savedInstanceState, MotionDialogBuilder.standard(context))
					.setView(layout.first).setPositiveButton(android.R.string.ok, (d, which) -> {
						Integer value = parse(input.getText());
						if (value != null) ConcurrentUtils.HANDLER.post(() -> setValue(value));
					}).create();
			Runnable validate = () -> {
				Integer value = parse(input.getText());
				input.setError(value == null ? context.getString(R.string.post_text_scale_range) : null);
				com.mishiranu.dashchan.widget.InterfaceAppearance.fieldError(input, value == null);
				if (value != null) {
					seek.setProgress(value - Preferences.MIN_TEXT_SCALE);
					updatePreview.accept(value);
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
