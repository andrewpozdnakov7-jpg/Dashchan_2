package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.ArrayList;

public class ExperimentalFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		refreshPreferences();
	}

	@Nullable
	private CheckPreference addExperimentalToggle(ExperimentalPreferenceDescriptors.Toggle toggle) {
		return toggle.available
				? addCheck(true, toggle.key, toggle.defaultValue, toggle.titleResId, toggle.summaryResId) : null;
	}

	private void refreshPreferences() {
		if (getView() == null) {
			return;
		}
		removeAllPreferences();
		if (ExperimentalPreferenceDescriptors.interfaceMotion().available) {
			addButton(R.string.material3_design_settings, R.string.material3_design_settings_summary)
					.setOnClickListener(p -> ((FragmentHandler) requireActivity())
							.pushFragment(new Material3DesignFragment()));
		}
		ArrayList<String> toolbarValues = new ArrayList<>();
		ArrayList<CharSequence> toolbarEntries = new ArrayList<>();
		for (Preferences.ThreadToolbarAction action : Preferences.ThreadToolbarAction.values()) {
			toolbarValues.add(action.value);
			toolbarEntries.add(getString(action.titleResId));
		}
		addList(Preferences.KEY_THREAD_TOOLBAR_ACTION, toolbarValues,
				Preferences.DEFAULT_THREAD_TOOLBAR_ACTION.value, R.string.thread_toolbar_action, toolbarEntries);
		addCheck(true, Preferences.KEY_S24_ULTRA_ONEUI9_TOOLBAR_FIX, Preferences.DEFAULT_S24_ULTRA_ONEUI9_TOOLBAR_FIX,
				R.string.s24_ultra_oneui9_toolbar_fix, R.string.s24_ultra_oneui9_toolbar_fix_summary);
		addButton(R.string.toolbar_title_sizes, R.string.toolbar_title_sizes__summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new ToolbarTitleSettingsFragment()));
		addExperimentalToggle(ExperimentalPreferenceDescriptors.threadFabMenu());
		addButton(R.string.compression_title, R.string.compression_summary).setOnClickListener(p ->
				((FragmentHandler) requireActivity()).pushFragment(new VideoCompressionFragment()));
		CheckPreference wallpaperPreference = addCheck(true, Preferences.KEY_WALLPAPER_ENABLED,
				Preferences.DEFAULT_WALLPAPER_ENABLED, R.string.wallpaper_background,
				R.string.wallpaper_background__summary);
		wallpaperPreference.setOnAfterChangeListener(p -> {
			refreshPreferences();
			requireActivity().recreate();
		});
		if (wallpaperPreference.getValue()) {
			addButton(R.string.configure_wallpaper, R.string.configure_wallpaper__summary)
					.setOnClickListener(p -> ((FragmentHandler) requireActivity())
							.pushFragment(new WallpaperFragment()));
		}

	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.experimental_features), null);
	}

	@Override
	public void onResume() {
		super.onResume();
		refreshPreferences();
	}

}
