package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;

public class NavigationDrawerSettingsFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		addButton(R.string.drawer_section_order, R.string.drawer_section_order_entry_summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new DrawerOrderFragment()));
		addButton(R.string.page_cleanup, R.string.page_cleanup_summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new PageCleanupSettingsFragment()));
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
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.navigation_drawer), null);
	}
}
