package com.mishiranu.dashchan.ui.preference;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.StateActivity;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.util.SystemNavigationMode;

/** System gesture information and local Back behavior in the posting form. */
public class PredictiveBackSettingsFragment extends PreferenceFragment {
	private CheckPreference posting;
	private Preference<Void> status;
	private final Runnable navigationChanged = this::refreshAvailability;
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	private static CharSequence entrySummary(Context context) {
		return context.getString(Build.VERSION.SDK_INT < 33 ? R.string.predictive_back_requires_android
				: SystemNavigationMode.isPredictiveBackEnabled()
						? R.string.predictive_back_settings_summary : R.string.predictive_back_enable_system_gestures);
	}
	@Override public void onViewCreated(@NonNull View view, Bundle state) {
		super.onViewCreated(view, state);
		if (!BuildConfig.ENABLE_PREDICTIVE_BACK) return;
		status = addButton(getString(R.string.predictive_back_title), p -> entrySummary(requireContext()));
		status.setSelectable(false);
		posting = addCheck(true, Preferences.KEY_POSTING_BACK_SINGLE_GESTURE, Preferences.DEFAULT_POSTING_BACK_SINGLE_GESTURE,
				R.string.posting_back_single_gesture, Build.VERSION.SDK_INT >= 34
						? R.string.posting_back_single_gesture_summary : R.string.posting_back_single_gesture_requires_android);
		posting.setOnAfterChangeListener(p -> ((StateActivity) requireActivity()).updateSystemBackCallback());
		refreshAvailability();
	}
	private void refreshAvailability() {
		if (getView() == null || posting == null) return;
		boolean postingEnabled = SystemNavigationMode.isPredictiveBackEnabled() && Build.VERSION.SDK_INT >= 34;
		if (posting.isEnabled() != postingEnabled) posting.setEnabled(postingEnabled);
		status.invalidate();
	}
	@Override public void onStart() {
		super.onStart(); SystemNavigationMode.addListener(navigationChanged);
	}
	@Override public void onResume() {
		super.onResume(); SystemNavigationMode.refresh(requireActivity().getWindow().getDecorView()); refreshAvailability();
	}
	@Override public void onStop() {
		SystemNavigationMode.removeListener(navigationChanged); super.onStop();
	}
	@Override public void onDestroyView() {
		SystemNavigationMode.removeListener(navigationChanged);
		posting = null; status = null; super.onDestroyView();
	}
	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.gesture_controls), null);
	}
}
