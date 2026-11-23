package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import androidx.fragment.app.DialogFragment;

/** Dismisses a confirmation restored from the version that coupled the two preferences. */
public class PredictiveBackPreferenceDialog extends DialogFragment {
	@Override public void onCreate(Bundle state) {
		super.onCreate(state);
		setShowsDialog(false);
	}

	@Override public void onStart() {
		super.onStart();
		dismissAllowingStateLoss();
	}
}
