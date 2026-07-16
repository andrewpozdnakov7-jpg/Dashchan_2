package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
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
		ChanManager manager = ChanManager.getInstance();
		LinkedHashMap<String, Chan> chans = new LinkedHashMap<>();
		for (Chan chan : manager.getAllChans()) {
			chans.put(chan.name, chan);
		}
		addChanPreference(chans.remove("dvach"));
		addChanPreference(chans.remove("fourchan"));
		for (Chan chan : chans.values()) {
			addChanPreference(chan);
		}
		addForumPreference("pikabu", R.string.forum_pikabu, 0,
				() -> showDevelopmentDialog(R.string.pikabu_extension_in_development));
		addForumPreference("gosuslugi", R.string.forum_gosuslugi, 0,
				() -> showDevelopmentDialog(R.string.gosuslugi_authorization_in_development));
	}

	private void addChanPreference(Chan chan) {
		if (chan == null) {
			return;
		}
		int titleResId = "dvach".equals(chan.name) ? R.string.forum_dvach
				: "fourchan".equals(chan.name) ? R.string.forum_fourchan : 0;
		CharSequence title = titleResId != 0 ? getString(titleResId) : chan.configuration.getTitle();
		CharSequence summary = "fourchan".equals(chan.name) ? getString(R.string.read_only) : null;
		addForumPreference(chan.name, title, summary, () -> ((FragmentHandler) requireActivity())
				.pushFragment(new ChanFragment(chan.name)));
	}

	private void addForumPreference(String name, int titleResId, int summaryResId, Runnable onOpen) {
		addForumPreference(name, getString(titleResId), summaryResId != 0 ? getString(summaryResId) : null, onOpen);
	}

	private void addForumPreference(String name, CharSequence title, CharSequence summary, Runnable onOpen) {
		ForumPreference preference = new ForumPreference(requireContext(), Preferences.getChanEnabledKey(name),
				"dvach".equals(name), title, summary);
		addPreference(preference, true);
		preference.setOnClickListener(p -> {
			if (p.getValue()) {
				onOpen.run();
			}
		});
	}

	private void showDevelopmentDialog(int messageResId) {
		new AlertDialog.Builder(requireContext())
				.setMessage(messageResId)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private static class ForumPreference extends CheckPreference {
		public ForumPreference(Context context, String key, boolean defaultValue,
				CharSequence title, CharSequence summary) {
			super(context, key, defaultValue, title, summary);
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
			holder.check.setOnClickListener(v -> setValue(!getValue()));
			return holder;
		}

		@Override
		public void bindViewHolder(ViewHolder viewHolder) {
			super.bindViewHolder(viewHolder);
			viewHolder.title.setEnabled(getValue());
			viewHolder.summary.setEnabled(getValue());
		}
	}
}
