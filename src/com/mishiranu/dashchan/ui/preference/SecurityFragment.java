package com.mishiranu.dashchan.ui.preference;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.security.AppLockActivity;
import com.mishiranu.dashchan.content.security.SecurityStore;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.io.IOException;
import java.util.ArrayList;

public final class SecurityFragment extends PreferenceFragment {
	private final ArrayList<Preference<?>> items = new ArrayList<>();
	private final ArrayList<Preference<?>> protectedItems = new ArrayList<>();
	private CheckPreference biometricToggle, downloadsToggle;
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }
	@Override public void onViewCreated(@NonNull View view, Bundle state) {
		super.onViewCreated(view, state);
		button(R.string.security_primary_pin, AppLockActivity.PRIMARY, "primary");
		biometricToggle = addCheck(false, null, false, R.string.security_biometric, R.string.security_biometric_summary);
		biometricToggle.setOnClickListener(p -> open(AppLockActivity.BIOMETRIC)); protectedItems.add(biometricToggle);
		button(R.string.security_additional_pin, AppLockActivity.ADDITIONAL, "additional");
		downloadsToggle = addCheck(false, null, false, R.string.security_delete_downloads, R.string.security_downloads_toggle_summary);
		downloadsToggle.setOnClickListener(p -> open(AppLockActivity.DOWNLOADS)); protectedItems.add(downloadsToggle);
		Preference<?> downloads = addButton(getString(R.string.security_approved_directory), p -> {
			try { return SecurityStore.update(requireContext(), s -> s.optString("delete_downloads_label", getString(R.string.security_disabled))); }
			catch (IOException e) { return getString(R.string.security_unavailable); }
		});
		downloads.setSelectable(false); items.add(downloads);
		addButton(R.string.security_delete_scope, R.string.security_delete_warning).setSelectable(false);
		addButton(getString(R.string.security_downloads_result), p -> {
			try { return SecurityStore.update(requireContext(), s -> s.optBoolean("external_cleanup_incomplete"))
					? getString(R.string.security_downloads_failed) : getString(R.string.security_downloads_optional); }
			catch (IOException e) { return getString(R.string.security_unavailable); }
		}).setSelectable(false);
		addButton(R.string.security_autolock, R.string.security_autolock_summary).setSelectable(false);
		Preference<?> remove = addButton(R.string.security_remove_additional, 0);
		remove.setOnClickListener(p -> open(AppLockActivity.REMOVE_ADDITIONAL)); protectedItems.add(remove);
		Preference<?> disable = addButton(R.string.security_disable, 0);
		disable.setOnClickListener(p -> open(AppLockActivity.DISABLE)); protectedItems.add(disable);
		addButton(R.string.security_limits, R.string.security_limits_summary).setSelectable(false);
	}
	private void button(int title, String action, String field) {
		Preference<?> item = addButton(getString(title), p -> {
			try { return getString(SecurityStore.update(requireContext(), s -> "biometric".equals(field)
					? s.optBoolean(field) : s.has(field)) ? R.string.security_enabled : R.string.security_disabled); }
			catch (IOException e) { return getString(R.string.security_unavailable); }
		});
		item.setOnClickListener(p -> open(action)); items.add(item);
		if (!AppLockActivity.PRIMARY.equals(action)) protectedItems.add(item);
	}
	private void open(String action) {
		startActivity(new Intent(requireContext(), AppLockActivity.class).putExtra("security_action", action));
	}
	@Override public void onResume() {
		super.onResume();
		boolean enabled = false;
		try { enabled = SecurityStore.enabled(requireContext()); } catch (IOException ignored) {}
		for (Preference<?> item : protectedItems) item.setEnabled(enabled);
		try {
			biometricToggle.setValue(SecurityStore.update(requireContext(), s -> s.optBoolean("biometric")));
			downloadsToggle.setValue(SecurityStore.update(requireContext(), s -> s.has("delete_downloads_uri")));
			downloadsToggle.setEnabled(enabled && SecurityStore.update(requireContext(), s -> s.has("additional")));
		} catch (IOException ignored) { biometricToggle.setValue(false); downloadsToggle.setValue(false); }
		for (Preference<?> item : items) item.invalidate();
	}
	@Override public void onDestroyView() {
		items.clear(); protectedItems.clear(); biometricToggle = null; downloadsToggle = null; super.onDestroyView();
	}
	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.security_title), null);
	}
}
