package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;

public class AccessibilityFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		String warning = getString(R.string.large_text_layout_warning).replace("%", "%%");
		String scaleFormat = ResourceUtils.getColonString(getResources(), R.string.scale, "%d%%")
				+ "\n" + warning;
		addSeek(Preferences.KEY_TEXT_SCALE, Preferences.DEFAULT_TEXT_SCALE,
				getString(R.string.text_scale), scaleFormat, null,
				Preferences.MIN_TEXT_SCALE, Preferences.MAX_TEXT_SCALE, Preferences.STEP_TEXT_SCALE)
				.setOnAfterChangeListener(p -> requireActivity().recreate());
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.accessibility), null);
	}
}
