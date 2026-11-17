package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;

public class PageCleanupSettingsFragment extends PreferenceFragment {
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	@Override public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		addCheck(true, Preferences.KEY_PAGE_CLEANUP_ENABLED, false, R.string.page_cleanup_enabled, 0);
		addCheck(true, Preferences.KEY_PAGE_CLEANUP_DELETED, true, R.string.page_cleanup_deleted,
				R.string.page_cleanup_deleted_summary);
		ArrayList<CharSequence> ages = new ArrayList<>();
		for (String value : Preferences.PAGE_CLEANUP_AGES) {
			int days = Integer.parseInt(value);
			ages.add(days == 0 ? getString(R.string.page_cleanup_age_unlimited)
					: getResources().getQuantityString(R.plurals.page_cleanup_days, days, days));
		}
		addList(Preferences.KEY_PAGE_CLEANUP_AGE, Arrays.asList(Preferences.PAGE_CLEANUP_AGES), "0",
				R.string.page_cleanup_age, ages);
		ArrayList<CharSequence> limits = new ArrayList<>();
		for (String value : Preferences.PAGE_CLEANUP_LIMITS) {
			int count = Integer.parseInt(value);
			limits.add(count == 0 ? getString(R.string.page_cleanup_count_unlimited)
					: getResources().getQuantityString(R.plurals.page_cleanup_threads, count, count));
		}
		addList(Preferences.KEY_PAGE_CLEANUP_LIMIT, Arrays.asList(Preferences.PAGE_CLEANUP_LIMITS), "0",
				R.string.page_cleanup_limit, limits);
		addCheck(true, Preferences.KEY_PAGE_CLEANUP_KEEP_FAVORITES, true,
				R.string.page_cleanup_keep_favorites, R.string.page_cleanup_keep_favorites_summary);
		for (String key : new String[] {Preferences.KEY_PAGE_CLEANUP_DELETED, Preferences.KEY_PAGE_CLEANUP_AGE,
				Preferences.KEY_PAGE_CLEANUP_LIMIT, Preferences.KEY_PAGE_CLEANUP_KEEP_FAVORITES}) {
			addDependency(key, Preferences.KEY_PAGE_CLEANUP_ENABLED, true);
		}
		addButton(0, R.string.page_cleanup_explanation).setSelectable(false);
	}

	@Override public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.page_cleanup), null);
	}
}
