package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.PopupColors;
import com.mishiranu.dashchan.widget.ThemeEngine;

public class PopupAppearanceFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		refreshPreferences();
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.popup_appearance), null);
	}

	private void refreshPreferences() {
		if (getView() == null) {
			return;
		}
		removeAllPreferences();
		addPreference(new AppearancePreference(), false);
		if (Preferences.getPopupColorMode() == Preferences.PopupColorMode.CUSTOM) {
			addColor(Preferences.KEY_POPUP_BACKGROUND, R.string.popup_background_color, Preferences::getPopupBackground);
			addColor(Preferences.KEY_POPUP_FOREGROUND, R.string.popup_text_color, Preferences::getPopupForeground);
		}
		if (Preferences.getPopupColorMode() == Preferences.PopupColorMode.CUSTOM
				&& ColorUtils.calculateContrast(Preferences.getPopupForeground(), Preferences.getPopupBackground()) < 4.5) {
			addButton(R.string.popup_low_contrast, R.string.popup_low_contrast__summary).setEnabled(false);
		}
		addButton(R.string.popup_reset, 0).setOnClickListener(p -> {
			Preferences.resetPopupColors();
			refreshPreferences();
			showPreview();
		});
	}

	private void addColor(String key, int title, PostMarksColorsFragment.ColorProvider provider) {
		PostMarksColorsFragment.ColorPreference preference = new PostMarksColorsFragment.ColorPreference(
				requireContext(), key, getString(title), provider);
		preference.setOnClickListener(p -> PopupColorDialog.newInstance(key, title)
				.show(getChildFragmentManager(), key));
		addPreference(preference, false);
	}

	void setCustomColor(String key, int color) {
		if (Preferences.KEY_POPUP_BACKGROUND.equals(key) || Preferences.KEY_POPUP_FOREGROUND.equals(key)) {
			Preferences.PREFERENCES.edit().put(key, color | 0xff000000).close();
			refreshPreferences();
		}
	}

	private void showPreview() {
		ClickableToast.show(getString(R.string.popup_preview_message), null,
				new ClickableToast.Button(android.R.string.ok, false, null));
	}

	private class AppearancePreference extends Preference.Runtime<Void> {
		AppearancePreference() {
			super(PopupAppearanceFragment.this.requireContext(), Preferences.KEY_POPUP_COLOR_MODE, null, null, null);
		}

		@Override
		public ViewType getViewType() { return ViewType.POPUP_APPEARANCE; }

		@Override
		public ViewHolder createViewHolder(ViewGroup parent) {
			float density = ResourceUtils.obtainDensity(parent);
			int padding = Math.round(16 * density);
			LinearLayout layout = new LinearLayout(context);
			layout.setOrientation(LinearLayout.VERTICAL);
			layout.setPadding(padding, padding, padding, padding);
			layout.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
			TextView caption = new TextView(context);
			ThemeEngine.applyStyle(caption);
			caption.setText(R.string.popup_colors_description);
			caption.setTextColor(ThemeEngine.getTheme(context).meta);
			layout.addView(caption);
			TextView preview = new TextView(context);
			preview.setTextSize(14);
			preview.setGravity(android.view.Gravity.CENTER);
			preview.setPadding(padding, padding, padding, padding);
			preview.setForceDarkAllowed(false);
			preview.setText(R.string.popup_preview_message);
			preview.setContentDescription(getString(R.string.popup_preview));
			preview.setOnClickListener(v -> showPreview());
			LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, -2);
			previewParams.topMargin = padding;
			previewParams.bottomMargin = padding;
			layout.addView(preview, previewParams);
			RadioGroup choices = new RadioGroup(context);
			for (Preferences.PopupColorMode mode : Preferences.PopupColorMode.values()) {
				RadioButton radio = new RadioButton(context);
				radio.setId(mode.ordinal() + 1);
				radio.setText(mode.titleResId);
				radio.setMinHeight(Math.round(48 * density));
				choices.addView(radio, new RadioGroup.LayoutParams(-1, -2));
			}
			layout.addView(choices);
			return new AppearanceHolder(layout, preview, choices);
		}

		@Override
		public void bindViewHolder(ViewHolder holder) {
			AppearanceHolder h = (AppearanceHolder) holder;
			PopupColors colors = PopupColors.obtain(context);
			GradientDrawable background = new GradientDrawable();
			background.setColor(colors.background);
			background.setCornerRadius(8 * ResourceUtils.obtainDensity(context));
			h.preview.setBackground(background);
			h.preview.setTextColor(colors.foreground);
			h.choices.setOnCheckedChangeListener(null);
			h.choices.check(Preferences.getPopupColorMode().ordinal() + 1);
			h.choices.setOnCheckedChangeListener((group, id) -> {
				if (id > 0 && id <= Preferences.PopupColorMode.values().length) {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_POPUP_COLOR_MODE,
							Preferences.PopupColorMode.values()[id - 1].value).close();
					refreshPreferences();
				}
			});
		}
	}

	private static class AppearanceHolder extends Preference.ViewHolder {
		final TextView preview;
		final RadioGroup choices;

		AppearanceHolder(View view, TextView preview, RadioGroup choices) {
			super(view, null, null, null);
			this.preview = preview;
			this.choices = choices;
		}
	}
}
