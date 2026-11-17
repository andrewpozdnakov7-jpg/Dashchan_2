package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.util.SystemNavigationMode;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Appearance controls retain their existing keys, dependencies and dialog behavior. */
public class AppearanceSettingsFragment extends PreferenceFragment {
	private Preference<Void> predictiveBackEntry;
	private final Runnable navigationChanged = this::refreshPredictiveEntry;
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }
	@Override public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		addButton(R.string.post_text_sizes, R.string.post_text_sizes_summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new PostTextSizesFragment()));
		addList(Preferences.KEY_TOUCH_FEEDBACK, Arrays.asList("normal", "dimmed", "off"),
				Preferences.DEFAULT_TOUCH_FEEDBACK, R.string.touch_feedback,
				Arrays.asList(getString(R.string.touch_feedback_normal),
						getString(R.string.touch_feedback_dimmed), getString(R.string.touch_feedback_off)))
				.setOnAfterChangeListener(p -> requireActivity().recreate());
		if ("dimmed".equals(Preferences.PREFERENCES.getString(Preferences.KEY_TOUCH_FEEDBACK,
				Preferences.DEFAULT_TOUCH_FEEDBACK))) {
			ThreadArrowTransparencyPreference intensity = new ThreadArrowTransparencyPreference(requireContext(),
					Preferences.KEY_TOUCH_FEEDBACK_INTENSITY, Preferences.DEFAULT_TOUCH_FEEDBACK_INTENSITY,
					R.string.touch_feedback_intensity);
			addDialogPreference(intensity);
			intensity.setOnAfterChangeListener(p -> requireActivity().recreate());
		}
		String scaleFormat = ResourceUtils.getColonString(getResources(), R.string.scale, "%d%%");
		addSeek(Preferences.KEY_THUMBNAILS_SCALE, Preferences.DEFAULT_THUMBNAILS_SCALE,
				getString(R.string.thumbnail_scale), scaleFormat, null,
				Preferences.MIN_THUMBNAILS_SCALE, Preferences.MAX_THUMBNAILS_SCALE, Preferences.STEP_THUMBNAILS_SCALE)
				.setOnAfterChangeListener(p -> requireActivity().recreate());
		addCheck(true, Preferences.KEY_CUT_THUMBNAILS, Preferences.DEFAULT_CUT_THUMBNAILS,
				R.string.crop_thumbnails, R.string.crop_thumbnails__summary);
		addCheck(true, Preferences.KEY_ACTIVE_SCROLLBAR, Preferences.DEFAULT_ACTIVE_SCROLLBAR,
				R.string.active_scrollbar, 0);
		addCheck(true, Preferences.KEY_SCROLL_THREAD_GALLERY, Preferences.DEFAULT_SCROLL_THREAD_GALLERY,
				R.string.scroll_thread_when_scrolling_gallery, 0);
		addButton(R.string.themes, 0).setOnClickListener(p -> ((FragmentHandler) requireActivity())
				.pushFragment(new ThemesFragment()));
		addButton(R.string.popup_appearance, R.string.popup_appearance__summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new PopupAppearanceFragment()));
		addCheck(true, Preferences.KEY_ROUNDED_DIALOGS, Preferences.DEFAULT_ROUNDED_DIALOGS,
				R.string.rounded_dialogs, R.string.rounded_dialogs__summary);
		RoundedDialogsRadiusPreference roundedDialogsRadiusPreference =
				new RoundedDialogsRadiusPreference(requireContext());
		addDialogPreference(roundedDialogsRadiusPreference);
		addDependency(Preferences.KEY_ROUNDED_DIALOGS_RADIUS, Preferences.KEY_ROUNDED_DIALOGS, true);
		List<String> lightThemeValues = new ArrayList<>();
		List<CharSequence> lightThemeEntries = new ArrayList<>();
		List<String> darkThemeValues = new ArrayList<>();
		List<CharSequence> darkThemeEntries = new ArrayList<>();
		for (ThemeEngine.Theme theme : ThemeEngine.getThemes()) {
			List<String> values = theme.base == ThemeEngine.Theme.Base.DARK ? darkThemeValues : lightThemeValues;
			List<CharSequence> entries = theme.base == ThemeEngine.Theme.Base.DARK
					? darkThemeEntries : lightThemeEntries;
			values.add(theme.name);
			entries.add(theme.name);
		}
		addCheck(true, Preferences.KEY_AUTOMATIC_DAY_NIGHT_THEME,
				Preferences.DEFAULT_AUTOMATIC_DAY_NIGHT_THEME, R.string.automatic_day_night_themes,
				R.string.automatic_day_night_themes__summary)
				.setOnAfterChangeListener(p -> requireActivity().recreate());
		addList(Preferences.KEY_DAY_THEME, lightThemeValues, lightThemeValues.get(0),
				R.string.day_theme, lightThemeEntries).setOnAfterChangeListener(p -> requireActivity().recreate());
		addList(Preferences.KEY_NIGHT_THEME, darkThemeValues, darkThemeValues.get(0),
				R.string.night_theme, darkThemeEntries).setOnAfterChangeListener(p -> requireActivity().recreate());
		addDependency(Preferences.KEY_DAY_THEME, Preferences.KEY_AUTOMATIC_DAY_NIGHT_THEME, true);
		addDependency(Preferences.KEY_NIGHT_THEME, Preferences.KEY_AUTOMATIC_DAY_NIGHT_THEME, true);
		if (BuildConfig.ENABLE_PREDICTIVE_BACK) {
			predictiveBackEntry = addButton(getString(R.string.predictive_back_title),
					p -> PredictiveBackSettingsFragment.entrySummary(requireContext()));
			predictiveBackEntry.setOnClickListener(p -> {
				if (PredictiveBackSettingsFragment.isAvailable()) ((FragmentHandler) requireActivity())
						.pushFragment(new PredictiveBackSettingsFragment());
			});
			refreshPredictiveEntry();
		}

	}

	private void refreshPredictiveEntry() {
		if (getView() == null || predictiveBackEntry == null) return;
		boolean enabled = PredictiveBackSettingsFragment.isAvailable();
		if (predictiveBackEntry.isEnabled() != enabled) predictiveBackEntry.setEnabled(enabled);
		predictiveBackEntry.invalidate();
	}
	@Override public void onStart() {
		super.onStart(); SystemNavigationMode.addListener(navigationChanged);
	}
	@Override public void onResume() {
		super.onResume(); SystemNavigationMode.refresh(requireActivity().getWindow().getDecorView()); refreshPredictiveEntry();
	}
	@Override public void onStop() {
		SystemNavigationMode.removeListener(navigationChanged); super.onStop();
	}

	@Override public void onDestroyView() {
		SystemNavigationMode.removeListener(navigationChanged); predictiveBackEntry = null;
		super.onDestroyView();
	}
	@Override public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.appearance), null);
	}
}
