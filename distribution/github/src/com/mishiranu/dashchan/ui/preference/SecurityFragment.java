package com.mishiranu.dashchan.ui.preference;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.security.AppLockActivity;
import com.mishiranu.dashchan.content.security.SecurityStore;
import com.mishiranu.dashchan.content.security.ReauthenticationPolicy;
import com.mishiranu.dashchan.content.security.AuthenticationPolicy;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.util.NavigationUtils;
import java.io.IOException;
import java.util.ArrayList;

/** Security settings are exposed only in the GitHub distribution while under development. */
public final class SecurityFragment extends PreferenceFragment {
	private static final String GUIDE_URL = "https://github.com/andrewpozdnakov7-jpg/Dashchan_2/wiki/Security";
	private final ArrayList<Preference<?>> items = new ArrayList<>();
	private final ArrayList<Preference<?>> protectedItems = new ArrayList<>();
	private CheckPreference downloadsToggle, screenshotsToggle, pipToggle;
	private AuthenticationPolicy.Mode displayedMode;
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }
	@Override public void onViewCreated(@NonNull View view, Bundle state) {
		super.onViewCreated(view, state);
		AuthenticationPolicy.Mode mode = null;
		try { mode = SecurityStore.authenticationMode(requireContext()); } catch (IOException ignored) {}
		populate(mode);
	}
	private void populate(AuthenticationPolicy.Mode mode) {
		removeAllPreferences(); items.clear(); protectedItems.clear(); displayedMode = mode;
		Preference<?> authentication = addButton(getString(R.string.security_authentication_mode), p -> {
			try { return getString(AppLockActivity.modeTitle(SecurityStore.authenticationMode(requireContext()))); }
			catch (IOException e) { return getString(R.string.security_unavailable); }
		});
		authentication.setOnClickListener(p -> open(AppLockActivity.AUTHENTICATION_MODE)); items.add(authentication);
		if (mode != AuthenticationPolicy.Mode.BIOMETRIC_ONLY) button(R.string.security_primary_pin, AppLockActivity.PRIMARY, "primary");
		Preference<?> interval = addButton(getString(R.string.security_reauthentication), p -> {
			try {
				int minutes = SecurityStore.update(requireContext(), s -> ReauthenticationPolicy.normalize(
						s.optInt(ReauthenticationPolicy.KEY, 0)));
				return minutes == 0 ? getString(R.string.security_reauthenticate_immediately)
						: getString(R.string.security_reauthenticate_minutes, minutes);
			} catch (IOException e) { return getString(R.string.security_unavailable); }
		});
		interval.setOnClickListener(p -> open(AppLockActivity.REAUTHENTICATION));
		items.add(interval); protectedItems.add(interval);
		screenshotsToggle = addCheck(false, null, false, R.string.security_allow_screenshots, 0);
		screenshotsToggle.setOnClickListener(p -> open(AppLockActivity.SCREENSHOTS)); protectedItems.add(screenshotsToggle);
		pipToggle = addCheck(false, null, false, R.string.security_hide_pip_video, R.string.security_hide_pip_video_summary);
		pipToggle.setOnClickListener(p -> open(AppLockActivity.PIP_VIDEO)); protectedItems.add(pipToggle);
		button(R.string.security_additional_pin, AppLockActivity.ADDITIONAL, "additional");
		downloadsToggle = addCheck(false, null, false, R.string.security_delete_downloads, 0);
		downloadsToggle.setOnClickListener(p -> open(AppLockActivity.DOWNLOADS)); protectedItems.add(downloadsToggle);
		Preference<?> downloads = addButton(getString(R.string.security_approved_directory), p -> {
			try { return SecurityStore.update(requireContext(), s -> s.optString("delete_downloads_label", getString(R.string.security_disabled))); }
			catch (IOException e) { return getString(R.string.security_unavailable); }
		});
		downloads.setSelectable(false); items.add(downloads);
		Preference<?> cleanup = addButton(getString(R.string.security_downloads_result), p -> {
			try { return SecurityStore.update(requireContext(), com.mishiranu.dashchan.content.security.ExternalCleanupLedger::hasIncomplete)
					? getString(R.string.security_cleanup_incomplete_short) : getString(R.string.security_cleanup_idle_short); }
			catch (IOException e) { return getString(R.string.security_unavailable); }
		});
		cleanup.setSelectable(false); items.add(cleanup);
		Preference<?> remove = addButton(R.string.security_remove_additional, 0);
		remove.setOnClickListener(p -> open(AppLockActivity.REMOVE_ADDITIONAL)); protectedItems.add(remove);
		Preference<?> disable = addButton(R.string.security_disable, 0);
		disable.setOnClickListener(p -> open(AppLockActivity.DISABLE)); protectedItems.add(disable);
		addButton(R.string.security_guide, 0).setOnClickListener(p ->
				NavigationUtils.openSystemBrowser(requireContext(), Uri.parse(GUIDE_URL
						+ ("ru".equals(getResources().getConfiguration().getLocales().get(0).getLanguage())
						? "" : "-English"))));
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
		try {
			AuthenticationPolicy.Mode mode = SecurityStore.authenticationMode(requireContext());
			if (mode != displayedMode) populate(mode);
			enabled = mode != null;
		} catch (IOException ignored) {}
		for (Preference<?> item : protectedItems) item.setEnabled(enabled);
		try {
			screenshotsToggle.setValue(enabled && SecurityStore.update(requireContext(), s -> s.optBoolean(SecurityStore.ALLOW_SCREENSHOTS, false)));
			pipToggle.setValue(enabled && SecurityStore.hidePipVideo(requireContext()));
			downloadsToggle.setValue(SecurityStore.update(requireContext(), s -> s.has("delete_downloads_uri")));
			downloadsToggle.setEnabled(enabled && SecurityStore.update(requireContext(), s -> s.has("additional")));
		} catch (IOException ignored) { downloadsToggle.setValue(false); screenshotsToggle.setValue(false); pipToggle.setValue(false); }
		for (Preference<?> item : items) item.invalidate();
	}
	@Override public void onDestroyView() {
		items.clear(); protectedItems.clear(); downloadsToggle = null; screenshotsToggle = null; pipToggle = null; displayedMode = null; super.onDestroyView();
	}
	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.security_title), null);
	}
}
