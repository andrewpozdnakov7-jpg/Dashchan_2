package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.ui.posting.EditorHeightPolicy;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.Arrays;

public class PostingAppearanceFragment extends PreferenceFragment {
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	@Override public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		refreshPreferences();
	}

	private void refreshPreferences() {
		removeAllPreferences();
		addList(Preferences.KEY_POSTING_EDITOR_HEIGHT, Arrays.asList("automatic", "compact", "expanded", "fixed"),
				"automatic", R.string.posting_editor_height,
				Arrays.asList(getString(R.string.posting_height_automatic), getString(R.string.posting_height_compact),
						getString(R.string.posting_height_expanded), getString(R.string.posting_height_fixed)),
				Arrays.asList(getString(R.string.posting_height_automatic_summary),
						getString(R.string.posting_height_compact_summary), getString(R.string.posting_height_expanded_summary),
						getString(R.string.posting_height_fixed_summary)))
				.setOnAfterChangeListener(p -> refreshPreferences());
		if (Preferences.getPostingEditorHeightMode() == EditorHeightPolicy.Mode.FIXED) {
			addSeek(Preferences.KEY_POSTING_EDITOR_LINES, EditorHeightPolicy.DEFAULT_LINES,
					R.string.posting_fixed_lines, R.string.posting_fixed_lines_format, null,
					EditorHeightPolicy.MIN_LINES, EditorHeightPolicy.MAX_LINES, 1);
		}
		// New independent keys inherit the old combined choice without clearing values/drafts.
		addCheck(true, Preferences.KEY_POSTING_HIDE_NAME, Preferences.isHidePersonalData(),
				R.string.posting_hide_name, R.string.posting_hide_field_summary);
		addCheck(true, Preferences.KEY_POSTING_HIDE_EMAIL, Preferences.isHidePersonalData(),
				R.string.posting_hide_email, R.string.posting_hide_field_summary);
		addCheck(true, Preferences.KEY_POSTING_HIDE_PASSWORD, Preferences.DEFAULT_POSTING_HIDE_PASSWORD,
				R.string.posting_hide_password, R.string.posting_hide_password_summary);
		addCheck(true, Preferences.KEY_POSTING_HIDE_SUBJECT, false,
				R.string.posting_hide_subject, R.string.posting_hide_subject_summary);
		addButton(R.string.posting_preview, R.string.posting_preview_summary).setOnClickListener(p ->
				((FragmentHandler) requireActivity()).pushFragment(new PostingPreviewFragment()));
	}

	@Override public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.posting_appearance), null);
	}
}
