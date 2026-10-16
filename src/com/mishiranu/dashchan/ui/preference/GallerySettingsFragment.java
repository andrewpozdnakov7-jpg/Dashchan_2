package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;

public class GallerySettingsFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		addCheck(true, Preferences.KEY_CLOSE_GALLERY_ON_BACKGROUND_TAP,
				Preferences.DEFAULT_CLOSE_GALLERY_ON_BACKGROUND_TAP, R.string.close_gallery_on_background_tap,
				R.string.close_gallery_on_background_tap__summary);
		addCheck(true, Preferences.KEY_CLOSE_GALLERY_GRID_ON_BACKGROUND_TAP,
				Preferences.DEFAULT_CLOSE_GALLERY_GRID_ON_BACKGROUND_TAP, R.string.close_gallery_grid_on_background_tap,
				R.string.close_gallery_grid_on_background_tap__summary);
		addCheck(true, Preferences.KEY_SCROLL_GALLERY_TO_CURRENT_FILE,
				Preferences.DEFAULT_SCROLL_GALLERY_TO_CURRENT_FILE, R.string.scroll_gallery_to_current_file,
				R.string.scroll_gallery_to_current_file__summary);
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.gallery), null);
	}
}
