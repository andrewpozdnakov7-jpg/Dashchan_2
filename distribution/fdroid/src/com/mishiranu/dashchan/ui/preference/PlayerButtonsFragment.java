package com.mishiranu.dashchan.ui.preference;

import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;

/** The experimental player and its layout editor are unavailable in this distribution. */
public class PlayerButtonsFragment extends PreferenceFragment {
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }
}
