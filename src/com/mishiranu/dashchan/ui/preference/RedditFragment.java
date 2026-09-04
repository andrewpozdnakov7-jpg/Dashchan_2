package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.RedditSectionsFragment;
import com.mishiranu.dashchan.ui.RedditWebReaderFragment;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;

public class RedditFragment extends PreferenceFragment {
	private Preference<Void> authorizationPreference;
	private Preference<Void> logoutPreference;

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		authorizationPreference = addButton(getString(R.string.reddit_sign_in), p ->
				Preferences.isRedditSignedIn() ? getString(R.string.reddit_signed_in)
						: getString(R.string.reddit_sign_in__summary));
		authorizationPreference.setOnClickListener(p -> ((FragmentHandler) requireActivity())
				.pushFragment(RedditWebReaderFragment.newAuthorizationInstance()));
		logoutPreference = addButton(R.string.reddit_sign_out, R.string.reddit_sign_out__summary);
		logoutPreference.setOnClickListener(p -> {
			RedditWebReaderFragment.clearRedditSession();
			updateAuthorizationPreferences();
			ClickableToast.show(R.string.completed);
		});
		updateAuthorizationPreferences();
		addButton(R.string.open_reddit_web_reader, R.string.reddit_public_web_reader__summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new RedditSectionsFragment()));
		addCheck(true, Preferences.KEY_REDDIT_WEB_READER_STYLE, Preferences.DEFAULT_REDDIT_WEB_READER_STYLE,
				R.string.reddit_slooop_style, R.string.reddit_slooop_style__summary);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.forum_reddit), null);
	}

	@Override
	public void onResume() {
		super.onResume();
		updateAuthorizationPreferences();
	}

	private void updateAuthorizationPreferences() {
		boolean signedIn = Preferences.isRedditSignedIn();
		if (authorizationPreference != null) {
			authorizationPreference.setEnabled(!signedIn);
			authorizationPreference.invalidate();
		}
		if (logoutPreference != null) {
			logoutPreference.setEnabled(signedIn);
			logoutPreference.invalidate();
		}
	}

	@Override
	public void onDestroyView() {
		authorizationPreference = null;
		logoutPreference = null;
		super.onDestroyView();
	}
}
