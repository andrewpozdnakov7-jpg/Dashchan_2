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

/** Existing checkbox/dialog presentation, with live system navigation availability. */
public class PredictiveBackSettingsFragment extends PreferenceFragment {
	private CheckPreference predictive, posting;
	private Preference<Void> status;
	private boolean confirming;
	private final Runnable navigationChanged = this::refreshAvailability;
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	static boolean isAvailable() {
		return BuildConfig.ENABLE_PREDICTIVE_BACK && Build.VERSION.SDK_INT >= 33
				&& SystemNavigationMode.get() != SystemNavigationMode.Mode.BUTTONS;
	}
	static CharSequence entrySummary(Context context) {
		return context.getString(Build.VERSION.SDK_INT < 33 ? R.string.predictive_back_requires_android
				: SystemNavigationMode.get() == SystemNavigationMode.Mode.BUTTONS
						? R.string.predictive_back_enable_system_gestures : R.string.predictive_back_settings_summary);
	}
	@Override public void onViewCreated(@NonNull View view, Bundle state) {
		super.onViewCreated(view, state);
		if (!BuildConfig.ENABLE_PREDICTIVE_BACK) return;
		status = addButton(getString(R.string.predictive_back_title), p -> entrySummary(requireContext()));
		status.setSelectable(false);
		predictive = addCheck(true, Preferences.KEY_PREDICTIVE_BACK, Preferences.DEFAULT_PREDICTIVE_BACK,
				R.string.enable_predictive_back, R.string.enable_predictive_back__summary);
		posting = addCheck(true, Preferences.KEY_POSTING_BACK_SINGLE_GESTURE, Preferences.DEFAULT_POSTING_BACK_SINGLE_GESTURE,
				R.string.posting_back_single_gesture, Build.VERSION.SDK_INT >= 34
						? R.string.posting_back_single_gesture_summary : R.string.posting_back_single_gesture_requires_android);
		predictive.setOnBeforeChangeListener((p, value) -> {
			if (value && !p.getValue() && !confirming && BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION
					&& !Preferences.isNewInterfaceMotionEnabled()) {
				PredictiveBackPreferenceDialog.show(getChildFragmentManager(), PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE);
				return false;
			}
			return true;
		});
		predictive.setOnAfterChangeListener(p -> {
			((StateActivity) requireActivity()).updateSystemBackCallback(); refreshAvailability();
		});
		posting.setOnAfterChangeListener(p -> ((StateActivity) requireActivity()).updateSystemBackCallback());
		getChildFragmentManager().setFragmentResultListener(PredictiveBackPreferenceDialog.RESULT, getViewLifecycleOwner(),
				(key, result) -> applyChoice(result.getInt(PredictiveBackPreferenceDialog.CHOICE)));
		refreshAvailability();
	}
	private void applyChoice(int choice) {
		if (predictive == null || !isAvailable() || !BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION
				|| choice != PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE
				&& choice != PredictiveBackPreferenceDialog.ENABLE_OLD_INTERFACE) return;
		try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
			if (choice == PredictiveBackPreferenceDialog.ENABLE_NEW_INTERFACE) {
				editor.put(Preferences.KEY_NEW_INTERFACE_MOTION, true).put(Preferences.KEY_NEW_POSTING_FORM, true)
						.put(Preferences.KEY_NEW_VIDEO_PLAYER, true);
			}
			editor.put(Preferences.KEY_PREDICTIVE_BACK, true);
		}
		if (getView() != null) refreshAvailability();
	}
	private void refreshAvailability() {
		if (getView() == null || predictive == null) return;
		boolean value = Preferences.isPredictiveBackEnabled();
		if (predictive.getValue() != value) {
			confirming = true;
			try { predictive.setValue(value); } finally { confirming = false; }
		}
		boolean available = isAvailable();
		if (predictive.isEnabled() != available) predictive.setEnabled(available);
		boolean postingEnabled = available && value && Build.VERSION.SDK_INT >= 34;
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
		predictive = posting = null; status = null; super.onDestroyView();
	}
	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.predictive_back_title), null);
	}
}
