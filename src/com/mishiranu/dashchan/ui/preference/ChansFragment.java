package com.mishiranu.dashchan.ui.preference;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import chan.content.Chan;
import chan.content.ChanManager;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.Collection;
import java.util.LinkedHashMap;

public class ChansFragment extends PreferenceFragment implements FragmentHandler.Callback {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		updateList();
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.forums), null);
	}

	@Override
	public void onChansChanged(Collection<String> changed, Collection<String> removed) {
		removeAllPreferences();
		updateList();
	}

	private void updateList() {
		addCheck(true, Preferences.KEY_AUTOMATIC_DOMAIN_SELECTION,
				Preferences.DEFAULT_AUTOMATIC_DOMAIN_SELECTION, R.string.automatic_domain_selection,
				R.string.automatic_domain_selection__summary);
		CheckPreference combinedFeedsPreference = addCheck(true, Preferences.KEY_COMBINED_FEEDS_ENABLED,
				Preferences.DEFAULT_COMBINED_FEEDS_ENABLED, R.string.combined_feeds,
				R.string.combined_feeds__summary);
		Preference<Void> configureCombinedFeedsPreference = addButton(R.string.configure_combined_feeds,
				R.string.configure_combined_feeds__summary);
		configureCombinedFeedsPreference.setEnabled(combinedFeedsPreference.getValue());
		configureCombinedFeedsPreference.setOnClickListener(p -> ((FragmentHandler) requireActivity())
				.pushFragment(new CombinedFeedsFragment()));
		combinedFeedsPreference.setOnAfterChangeListener(p ->
				configureCombinedFeedsPreference.setEnabled(p.getValue()));
		ChanManager manager = ChanManager.getInstance();
		LinkedHashMap<String, Chan> chans = new LinkedHashMap<>();
		for (Chan chan : manager.getAllChans()) {
			chans.put(chan.name, chan);
		}
		addChanPreference(chans.remove("dvach"));
		addChanPreference(chans.remove("fourchan"));
		addChanPreference(chans.remove("ejchan"));
		addChanPreference(chans.remove("apachan"));
		addChanPreference(chans.remove("arhivach"));
		addChanPreference(chans.remove("pikabu"));
		addForumPreference(Preferences.KEY_REDDIT_WEB_READER_ENABLED,
				Preferences.DEFAULT_REDDIT_WEB_READER_ENABLED, getString(R.string.forum_reddit),
				null, () -> ((FragmentHandler) requireActivity())
						.pushFragment(new RedditFragment()));
		for (Chan chan : chans.values()) {
			addChanPreference(chan);
		}
	}

	private void addChanPreference(Chan chan) {
		if (chan == null) {
			return;
		}
		int titleResId = "dvach".equals(chan.name) ? R.string.forum_dvach
				: "fourchan".equals(chan.name) ? R.string.forum_fourchan
				: "ejchan".equals(chan.name) ? R.string.forum_ejchan
				: "apachan".equals(chan.name) ? R.string.forum_apachan
				: "arhivach".equals(chan.name) ? R.string.forum_arhivach
				: "pikabu".equals(chan.name) ? R.string.forum_pikabu : 0;
		CharSequence title = titleResId != 0 ? getString(titleResId) : chan.configuration.getTitle();
		CharSequence summary = "fourchan".equals(chan.name) || "arhivach".equals(chan.name)
				? getString(R.string.read_only) : null;
		addForumPreference(chan.name, title, summary, () -> ((FragmentHandler) requireActivity())
				.pushFragment(new ChanFragment(chan.name)));
	}

	private void addForumPreference(String name, int titleResId, int summaryResId, Runnable onOpen) {
		addForumPreference(name, getString(titleResId), summaryResId != 0 ? getString(summaryResId) : null, onOpen);
	}

	private void addForumPreference(String name, CharSequence title, CharSequence summary, Runnable onOpen) {
		addForumPreference(Preferences.getChanEnabledKey(name), Preferences.isChanEnabledByDefault(name),
				title, summary, onOpen);
	}

	private void addForumPreference(String key, boolean defaultValue, CharSequence title, CharSequence summary,
			Runnable onOpen) {
		ForumPreference preference = new ForumPreference(requireContext(), key, defaultValue, title, summary);
		addPreference(preference, true);
		preference.setOnClickListener(p -> {
			if (p.getValue()) {
				onOpen.run();
			}
		});
	}

	private static class ForumPreference extends CheckPreference {
		public ForumPreference(Context context, String key, boolean defaultValue,
				CharSequence title, CharSequence summary) {
			super(context, key, defaultValue, title, summary);
		}

		@Override
		public ViewType getViewType() {
			return ViewType.FORUM;
		}

		@Override
		public CheckViewHolder createViewHolder(ViewGroup parent) {
			CheckViewHolder holder = super.createViewHolder(parent);
			holder.widgetFrame.removeView(holder.check);
			holder.widgetFrame.setVisibility(View.GONE);
			float density = ResourceUtils.obtainDensity(parent);
			LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
			layoutParams.setMarginEnd((int) (12f * density + 0.5f));
			((LinearLayout) holder.view).addView(holder.check, 0, layoutParams);
			holder.check.setClickable(true);
			holder.check.setFocusable(false);
			return holder;
		}

		@Override
		public void bindViewHolder(ViewHolder viewHolder) {
			super.bindViewHolder(viewHolder);
			if (viewHolder instanceof CheckViewHolder) {
				((CheckViewHolder) viewHolder).check.setOnClickListener(v -> setValue(!getValue()));
			}
			viewHolder.title.setEnabled(getValue());
			viewHolder.summary.setEnabled(getValue());
		}
	}
}
