package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;

public class AutohideSettingsFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.autohide_settings), null);
		addCheck(true, Preferences.KEY_REMOVE_HIDDEN_THREADS, Preferences.isRemoveHiddenThreads(),
				R.string.remove_hidden_threads, R.string.remove_hidden_threads__summary);
		addCheck(true, Preferences.KEY_REMOVE_HIDDEN_POSTS, Preferences.DEFAULT_REMOVE_HIDDEN_POSTS,
				R.string.remove_hidden_posts, R.string.remove_hidden_posts__summary);
	}
}
