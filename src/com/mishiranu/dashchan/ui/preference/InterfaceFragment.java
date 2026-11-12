package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.LauncherIconManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.Arrays;

public class InterfaceFragment extends PreferenceFragment {
	private Preference<Void> applicationLogoPreference;

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		addHeader(R.string.application_shortcut);
		addList(Preferences.KEY_APPLICATION_NAME, LauncherIconManager.getApplicationNames(),
				Preferences.DEFAULT_APPLICATION_NAME, R.string.application_name,
				Arrays.asList("Sloop", "Dashchan_2", "Двач", "Slooop", "Slopchan", "Slopchan_1",
						"Slopchan_2"))
				.setOnAfterChangeListener(p -> LauncherIconManager.apply(requireContext(), p.getValue()));
		if (LauncherIconManager.arePresetLogosReady()) {
			Preference<Void> logoPreference = addButton(getString(R.string.application_logo), preference ->
					getString(LauncherIconManager.getLogoOption(Preferences.getApplicationLogo()).titleResId));
			applicationLogoPreference = logoPreference;
			logoPreference.setOnClickListener(preference ->
					ApplicationLogoDialog.show(getChildFragmentManager()));
		}
		addButton(R.string.appearance, R.string.appearance_settings_summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new AppearanceSettingsFragment()));

		addHeader(R.string.navigation_drawer);
		addButton(R.string.drawer_section_order, R.string.drawer_section_order_entry_summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new DrawerOrderFragment()));
		addCheck(true, Preferences.KEY_COLLAPSE_LONG_OPEN_THREADS,
				Preferences.DEFAULT_COLLAPSE_LONG_OPEN_THREADS,
				R.string.collapse_long_open_threads,
				R.string.collapse_long_open_threads__summary);
		addList(Preferences.KEY_PAGES_LIST, enumList(Preferences.PagesListMode.values(), o -> o.value),
				Preferences.DEFAULT_PAGES_LIST.value, R.string.headers_order,
				enumResList(Preferences.PagesListMode.values(), o -> o.titleResId));
		addList(Preferences.KEY_DRAWER_INITIAL_POSITION,
				enumList(Preferences.DrawerInitialPosition.values(), o -> o.value),
				Preferences.DEFAULT_DRAWER_INITIAL_POSITION.value, R.string.initial_position,
				enumResList(Preferences.DrawerInitialPosition.values(), o -> o.titleResId));

		addHeader(R.string.threads_list);
		addCheck(true, Preferences.KEY_PAGE_BY_PAGE, Preferences.DEFAULT_PAGE_BY_PAGE,
				R.string.paged_board_navigation, R.string.paged_board_navigation__summary);
		addCheck(true, Preferences.KEY_REMOVE_HIDDEN_THREADS,
				Preferences.DEFAULT_REMOVE_HIDDEN_THREADS, R.string.remove_hidden_threads,
				R.string.remove_hidden_threads__summary);
		addCheck(true, Preferences.KEY_HIDE_THREADS_WITH_SWIPE,
				Preferences.DEFAULT_HIDE_THREADS_WITH_SWIPE, R.string.hide_threads_with_swipe, 0);

		addButton(R.string.posts_list, R.string.posts_list_settings_summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new PostsListSettingsFragment()));

		addHeader(R.string.submission_form);
		addButton(R.string.posting_appearance, R.string.posting_appearance_summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new PostingAppearanceFragment()));
		addCheck(true, Preferences.KEY_HUGE_CAPTCHA, Preferences.DEFAULT_HUGE_CAPTCHA,
				R.string.huge_captcha, 0);
	}

	void onApplicationLogoChanged() {
		if (applicationLogoPreference != null) {
			applicationLogoPreference.invalidate();
		}
	}

	@Override
	public void onDestroyView() {
		applicationLogoPreference = null;
		super.onDestroyView();
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.user_interface), null);
	}


}
