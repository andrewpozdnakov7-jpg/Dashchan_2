package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import chan.content.ChanManager;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.LocaleManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;

public class GeneralFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		addList(Preferences.KEY_LOCALE, LocaleManager.VALUES_LOCALE, LocaleManager.DEFAULT_LOCALE,
				R.string.language, LocaleManager.ENTRIES_LOCALE)
				.setOnAfterChangeListener(p -> requireActivity().recreate());

		addHeader(R.string.navigation);
		addCheck(true, Preferences.KEY_CLOSE_ON_BACK, Preferences.DEFAULT_CLOSE_ON_BACK,
				R.string.close_pages, R.string.close_pages__summary);
		addCheck(true, Preferences.KEY_RESTORE_PAGES, Preferences.DEFAULT_RESTORE_PAGES,
				R.string.restore_pages, R.string.restore_pages__summary);
		addCheck(true, Preferences.KEY_REMEMBER_HISTORY, Preferences.DEFAULT_REMEMBER_HISTORY,
				R.string.remember_history, 0);
		if (ChanManager.getInstance().hasMultipleAvailableChans()) {
			addCheck(true, Preferences.KEY_MERGE_CHANS, Preferences.DEFAULT_MERGE_CHANS,
					R.string.merge_pages, R.string.merge_pages__summary);
		}
		addCheck(true, Preferences.KEY_INTERNAL_BROWSER, Preferences.DEFAULT_INTERNAL_BROWSER,
				R.string.internal_browser, R.string.internal_browser__sumamry);
		addCheck(true, Preferences.KEY_PRIVATE_BROWSER, Preferences.DEFAULT_PRIVATE_BROWSER,
				R.string.private_browser, R.string.private_browser__summary);
		addDependency(Preferences.KEY_PRIVATE_BROWSER, Preferences.KEY_INTERNAL_BROWSER, true);

	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.general), null);
	}
}
