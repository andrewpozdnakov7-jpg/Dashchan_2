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

/** Opening this screen preserves choices; explicitly enabling the design enables its options. */
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
		CheckPreference player = addCheck(true, Preferences.KEY_NEW_VIDEO_PLAYER,
				Preferences.DEFAULT_NEW_VIDEO_PLAYER, R.string.material3_video_player,
				R.string.material3_video_player_summary);
		// Merely reopening/restoring the screen must not re-enable an opted-out feature.
		form.setEnabled(design.getValue());
		player.setEnabled(design.getValue());
		design.setOnAfterChangeListener(p -> onDesignToggled(p.getValue(), form, player));
	}

	static void onDesignToggled(boolean enabled, CheckPreference... options) {
		for (CheckPreference option : options) {
			option.setEnabled(enabled);
			if (enabled) option.setValue(true);
		}
	}

	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.material3_design_settings), null);
	}
}
