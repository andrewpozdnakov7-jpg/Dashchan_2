package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.Arrays;

public class ThreadQuickNavigationFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		addList(Preferences.KEY_THREAD_QUICK_NAVIGATION, Arrays.asList("disabled", "right", "left"),
				Preferences.DEFAULT_THREAD_QUICK_NAVIGATION, R.string.thread_quick_navigation,
				Arrays.asList(getString(R.string.thread_quick_navigation_disabled),
						getString(R.string.thread_quick_navigation_right),
						getString(R.string.thread_quick_navigation_left)));
		addDialogPreference(new ThreadArrowTransparencyPreference(requireContext()));
		addDependency(Preferences.KEY_THREAD_QUICK_NAVIGATION_TRANSPARENCY,
				Preferences.KEY_THREAD_QUICK_NAVIGATION, true, "right", "left");
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.thread_quick_navigation), null);
	}
}
