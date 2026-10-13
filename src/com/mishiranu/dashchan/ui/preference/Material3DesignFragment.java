package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;

/** The original design preference retains its key, default and saved choice. */
public class Material3DesignFragment extends PreferenceFragment {
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	@Override public void onViewCreated(@NonNull View view, Bundle state) {
		super.onViewCreated(view, state);
		if (!BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION) return;
		CheckPreference design = addCheck(true, Preferences.KEY_NEW_INTERFACE_MOTION,
				Preferences.DEFAULT_NEW_INTERFACE_MOTION, R.string.material3_enable_design,
				R.string.new_interface_motion_summary);
		CheckPreference form = addCheck(true, Preferences.KEY_NEW_POSTING_FORM,
				Preferences.DEFAULT_NEW_POSTING_FORM, R.string.material3_posting_form,
				R.string.material3_posting_form_summary);
		// Disabling the parent suspends the feature; it does not erase the child's saved choice.
		form.setEnabled(design.getValue());
		design.setOnAfterChangeListener(p -> form.setEnabled(p.getValue()));
	}

	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.material3_design_settings), null);
	}
}
