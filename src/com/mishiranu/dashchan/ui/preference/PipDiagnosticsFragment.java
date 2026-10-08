package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;

/** Retains the diagnostic entry without exposing experimental PiP mode switches. */
public class PipDiagnosticsFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		addButton(0, R.string.pip_diagnostic_hidden_summary).setSelectable(false);
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.pip_diagnostic_mode), null);
	}
}
