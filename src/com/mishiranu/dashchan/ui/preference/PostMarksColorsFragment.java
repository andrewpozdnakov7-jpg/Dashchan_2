package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.ListPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.Locale;

public class PostMarksColorsFragment extends PreferenceFragment {
	private ColorPreference userPostColorPreference;
	private ColorPreference replyColorPreference;

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		ListPreference modePreference = addList(Preferences.KEY_POST_MARKS_COLOR_MODE,
				enumList(Preferences.PostMarksColorMode.values(), mode -> mode.value),
				Preferences.DEFAULT_POST_MARKS_COLOR_MODE.value, R.string.post_marks_color_source,
				enumResList(Preferences.PostMarksColorMode.values(), mode -> mode.titleResId));

		addHeader(R.string.post_marks_colors_custom);
		userPostColorPreference = new ColorPreference(requireContext(), Preferences.KEY_USER_POST_MARK_COLOR,
				getString(R.string.user_posts_color), Preferences::getUserPostMarkColor);
		userPostColorPreference.setOnClickListener(preference -> ColorDialog.newInstance(
				Preferences.KEY_USER_POST_MARK_COLOR, R.string.user_posts_color)
				.show(getChildFragmentManager(), Preferences.KEY_USER_POST_MARK_COLOR));
		addPreference(userPostColorPreference, false);

		replyColorPreference = new ColorPreference(requireContext(), Preferences.KEY_REPLY_POST_MARK_COLOR,
				getString(R.string.replies_to_me_color), Preferences::getReplyPostMarkColor);
		replyColorPreference.setOnClickListener(preference -> ColorDialog.newInstance(
				Preferences.KEY_REPLY_POST_MARK_COLOR, R.string.replies_to_me_color)
				.show(getChildFragmentManager(), Preferences.KEY_REPLY_POST_MARK_COLOR));
		addPreference(replyColorPreference, false);

		addDependency(Preferences.KEY_USER_POST_MARK_COLOR, Preferences.KEY_POST_MARKS_COLOR_MODE,
				true, Preferences.PostMarksColorMode.CUSTOM.value);
		addDependency(Preferences.KEY_REPLY_POST_MARK_COLOR, Preferences.KEY_POST_MARKS_COLOR_MODE,
				true, Preferences.PostMarksColorMode.CUSTOM.value);

		addButton(R.string.reset_post_marks_colors, R.string.reset_post_marks_colors__summary)
				.setOnClickListener(preference -> {
					modePreference.setValue(Preferences.DEFAULT_POST_MARKS_COLOR_MODE.value);
					Preferences.resetPostMarksColors();
					userPostColorPreference.invalidate();
					replyColorPreference.invalidate();
					ClickableToast.show(R.string.post_marks_colors_reset);
				});
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		userPostColorPreference = null;
		replyColorPreference = null;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.post_marks_colors), null);
	}

	private void setCustomColor(String key, int color) {
		if (Preferences.KEY_USER_POST_MARK_COLOR.equals(key)) {
			Preferences.setUserPostMarkColor(color);
			if (userPostColorPreference != null) {
				userPostColorPreference.invalidate();
			}
		} else if (Preferences.KEY_REPLY_POST_MARK_COLOR.equals(key)) {
			Preferences.setReplyPostMarkColor(color);
			if (replyColorPreference != null) {
				replyColorPreference.invalidate();
			}
		}
	}

	private interface ColorProvider {
		int getColor();
	}

	private static class ColorPreference extends Preference.Runtime<Void> {
		private final ColorProvider colorProvider;

		private static class ColorViewHolder extends ViewHolder {
			public final View swatch;

			public ColorViewHolder(ViewHolder viewHolder, View swatch) {
				super(viewHolder);
				this.swatch = swatch;
			}
		}

		public ColorPreference(Context context, String key, CharSequence title, ColorProvider colorProvider) {
			super(context, key, null, title, preference -> formatColor(colorProvider.getColor()));
			this.colorProvider = colorProvider;
		}

		@Override
		public ViewType getViewType() {
			return ViewType.COLOR;
		}

		@Override
		public ViewHolder createViewHolder(ViewGroup parent) {
			ViewHolder viewHolder = super.createViewHolder(parent);
			viewHolder.widgetFrame.setVisibility(View.VISIBLE);
			float density = ResourceUtils.obtainDensity(parent);
			View swatch = new View(parent.getContext());
			int size = Math.round(32f * density);
			LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(size, size);
			layoutParams.setMarginEnd(Math.round(16f * density));
			swatch.setLayoutParams(layoutParams);
			viewHolder.widgetFrame.addView(swatch);
			return new ColorViewHolder(viewHolder, swatch);
		}

		@Override
		public void bindViewHolder(ViewHolder viewHolder) {
			super.bindViewHolder(viewHolder);
			ColorViewHolder colorViewHolder = (ColorViewHolder) viewHolder;
			setSwatch(colorViewHolder.swatch, colorProvider.getColor());
			colorViewHolder.swatch.setAlpha(isEnabled() ? 1f : 0.38f);
		}
	}

	public static class ColorDialog extends DialogFragment {
		private static final String EXTRA_KEY = "key";
		private static final String EXTRA_TITLE = "title";
		private static final int[] PRESET_COLORS = {
				0xff4cae4f, 0xfff76e64, 0xff1976d2, 0xff00acc1,
				0xff7b1fa2, 0xfff9a825, 0xfff57c00, 0xff616161};

		private SafePasteEditText colorEdit;
		private View preview;

		public static ColorDialog newInstance(String key, int titleResId) {
			ColorDialog dialog = new ColorDialog();
			Bundle args = new Bundle();
			args.putString(EXTRA_KEY, key);
			args.putInt(EXTRA_TITLE, titleResId);
			dialog.setArguments(args);
			return dialog;
		}

		@Override
		@NonNull
		public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
			Context context = requireContext();
			String key = requireArguments().getString(EXTRA_KEY);
			int color = Preferences.KEY_USER_POST_MARK_COLOR.equals(key)
					? Preferences.getUserPostMarkColor() : Preferences.getReplyPostMarkColor();
			float density = ResourceUtils.obtainDensity(context);
			int padding = Math.round(24f * density);

			LinearLayout layout = new LinearLayout(context);
			layout.setOrientation(LinearLayout.VERTICAL);
			layout.setPadding(padding, padding, padding, 0);

			preview = new View(context);
			LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, Math.round(56f * density));
			previewParams.bottomMargin = Math.round(12f * density);
			layout.addView(preview, previewParams);

			TextView paletteTitle = new TextView(context);
			ThemeEngine.applyStyle(paletteTitle);
			paletteTitle.setText(R.string.post_marks_color_palette);
			paletteTitle.setTextColor(ThemeEngine.getTheme(context).meta);
			layout.addView(paletteTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			LinearLayout palette = new LinearLayout(context);
			palette.setOrientation(LinearLayout.VERTICAL);
			for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
				LinearLayout row = new LinearLayout(context);
				row.setOrientation(LinearLayout.HORIZONTAL);
				for (int columnIndex = 0; columnIndex < 4; columnIndex++) {
					int presetColor = PRESET_COLORS[rowIndex * 4 + columnIndex];
					FrameLayout cell = new FrameLayout(context);
					ViewUtils.setSelectableItemBackground(cell);
					cell.setContentDescription(formatColor(presetColor));
					cell.setOnClickListener(view -> colorEdit.setText(formatColor(presetColor)));
					View swatch = new View(context);
					int swatchSize = Math.round(32f * density);
					FrameLayout.LayoutParams swatchParams = new FrameLayout.LayoutParams(swatchSize, swatchSize);
					swatchParams.gravity = android.view.Gravity.CENTER;
					cell.addView(swatch, swatchParams);
					setSwatch(swatch, presetColor);
					row.addView(cell, new LinearLayout.LayoutParams(0, Math.round(48f * density), 1f));
				}
				palette.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.WRAP_CONTENT));
			}
			LinearLayout.LayoutParams paletteParams = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			paletteParams.topMargin = Math.round(4f * density);
			paletteParams.bottomMargin = Math.round(8f * density);
			layout.addView(palette, paletteParams);

			colorEdit = new SafePasteEditText(context);
			colorEdit.setId(android.R.id.edit);
			colorEdit.setSingleLine(true);
			colorEdit.setSelectAllOnFocus(true);
			colorEdit.setHint("#RRGGBB");
			colorEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
					| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
			colorEdit.setFilters(new InputFilter[] {new InputFilter.AllCaps(), new InputFilter.LengthFilter(7)});
			colorEdit.setText(formatColor(color));
			colorEdit.addTextChangedListener(new TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
					Integer parsedColor = parseColor(s != null ? s.toString() : null);
					if (parsedColor != null) {
						setSwatch(preview, parsedColor);
					}
				}

				@Override
				public void afterTextChanged(Editable s) {}
			});
			layout.addView(colorEdit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			setSwatch(preview, color);

			return new AlertDialog.Builder(context)
					.setTitle(requireArguments().getInt(EXTRA_TITLE))
					.setView(layout)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok, null)
					.create();
		}

		@Override
		public void onStart() {
			super.onStart();
			AlertDialog dialog = (AlertDialog) getDialog();
			if (dialog != null) {
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
					Integer color = parseColor(colorEdit.getText().toString());
					if (color == null) {
						colorEdit.setError(getString(R.string.invalid_hex_color));
						return;
					}
					String key = requireArguments().getString(EXTRA_KEY);
					if (getParentFragment() instanceof PostMarksColorsFragment) {
						((PostMarksColorsFragment) getParentFragment()).setCustomColor(key, color);
					}
					dismiss();
				});
			}
		}
	}

	private static String formatColor(int color) {
		return String.format(Locale.US, "#%06X", color & 0x00ffffff);
	}

	private static Integer parseColor(String value) {
		if (value == null) {
			return null;
		}
		String hex = value.trim();
		if (hex.startsWith("#")) {
			hex = hex.substring(1);
		}
		if (!hex.matches("[0-9A-Fa-f]{6}")) {
			return null;
		}
		try {
			return Color.rgb(Integer.parseInt(hex.substring(0, 2), 16),
					Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static void setSwatch(View view, int color) {
		Context context = view.getContext();
		float density = ResourceUtils.obtainDensity(view);
		GradientDrawable drawable = new GradientDrawable();
		drawable.setColor(color | 0xff000000);
		drawable.setCornerRadius(4f * density);
		drawable.setStroke(Math.max(1, Math.round(density)), ThemeEngine.getTheme(context).meta);
		view.setBackground(drawable);
	}
}
