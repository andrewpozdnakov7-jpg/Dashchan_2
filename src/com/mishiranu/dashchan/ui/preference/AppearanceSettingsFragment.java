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
import com.mishiranu.dashchan.ui.StateActivity;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Appearance controls retain their existing keys, dependencies and dialog behavior. */
public class AppearanceSettingsFragment extends PreferenceFragment {
	private CheckPreference predictiveBackPreference;
	private boolean confirmingPredictiveBack;
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
			predictiveBackPreference = addCheck(true, Preferences.KEY_PREDICTIVE_BACK, Preferences.DEFAULT_PREDICTIVE_BACK,
					R.string.enable_predictive_back, R.string.enable_predictive_back__summary);
			predictiveBackPreference.setOnBeforeChangeListener((preference, value) -> {
				if (value && !preference.getValue() && !confirmingPredictiveBack &&
						BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION && !Preferences.isNewInterfaceMotionEnabled()) {
					PredictiveBackPreferenceDialog.show(getChildFragmentManager(),
							PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE);
					return false;
				}
				return true;
			});
			predictiveBackPreference.setOnAfterChangeListener(p ->
					((StateActivity) requireActivity()).updateSystemBackCallback());
			getChildFragmentManager().setFragmentResultListener(PredictiveBackPreferenceDialog.RESULT,
					getViewLifecycleOwner(), (key, result) -> applyPredictiveBackChoice(
							result.getInt(PredictiveBackPreferenceDialog.CHOICE)));
		}
		addButton(R.string.gesture_controls, R.string.gesture_controls__summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new GestureSettingsFragment()));

	}

	private void applyPredictiveBackChoice(int choice) {
		if (!BuildConfig.ENABLE_PREDICTIVE_BACK || predictiveBackPreference == null || !BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION ||
				(choice != PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE &&
				choice != PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE)) return;
		CheckPreference preference = predictiveBackPreference;
		// Publish the complete choice before preference listeners can recreate the Activity.
		try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
			if (choice == PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE) {
				editor.put(Preferences.KEY_NEW_INTERFACE_MOTION, true)
						.put(Preferences.KEY_NEW_POSTING_FORM, true).put(Preferences.KEY_NEW_VIDEO_PLAYER, true);
			}
			editor.put(Preferences.KEY_PREDICTIVE_BACK, true);
		}
		if (predictiveBackPreference != preference || getView() == null) return;
		confirmingPredictiveBack = true;
		try { preference.setValue(true); }
		finally { confirmingPredictiveBack = false; }
	}

	@Override public void onDestroyView() {
		predictiveBackPreference = null;
		super.onDestroyView();
	}
	@Override public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.appearance), null);
	}
}
