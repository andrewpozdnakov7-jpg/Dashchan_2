package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import android.util.Pair;
import com.mishiranu.dashchan.content.service.BackgroundWatcherWorker;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;

public class FavoritesFragment extends PreferenceFragment {
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }
	@Override public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		addList(Preferences.KEY_FAVORITES_ORDER, enumList(Preferences.FavoritesOrder.values(), o -> o.value),
				Preferences.DEFAULT_FAVORITES_ORDER.value, R.string.favorite_threads_order,
				enumResList(Preferences.FavoritesOrder.values(), o -> o.titleResId))
				.setOnAfterChangeListener(p -> FavoritesStorage.getInstance().sortIfNeeded());
		addCheck(true, Preferences.KEY_FAVORITES_GROUP_BY_BOARD, Preferences.DEFAULT_FAVORITES_GROUP_BY_BOARD,
				R.string.favorites_group_by_board, R.string.favorites_group_by_board_summary);
		addCheck(true, Preferences.KEY_FAVORITES_UNREAD_FIRST, Preferences.DEFAULT_FAVORITES_UNREAD_FIRST,
				R.string.favorites_unread_first, R.string.favorites_unread_first_summary);
		addList(Preferences.KEY_FAVORITE_ON_REPLY, enumList(Preferences.FavoriteOnReplyMode.values(), o -> o.value),
				Preferences.DEFAULT_FAVORITE_ON_REPLY.value, R.string.add_thread_on_reply,
				enumResList(Preferences.FavoriteOnReplyMode.values(), o -> o.titleResId));
		addHeader(R.string.favorites_watcher);
		addCheck(true, Preferences.KEY_WATCHER_WATCH_INITIALLY, Preferences.DEFAULT_WATCHER_WATCH_INITIALLY,
				R.string.watch_initially, R.string.watch_initially__summary);
		addSeek(Preferences.KEY_WATCHER_REFRESH_INTERVAL, Preferences.DEFAULT_WATCHER_REFRESH_INTERVAL,
				R.string.refresh_favorites, R.string.every_number_sec__format,
				new Pair<>(Preferences.DISABLED_WATCHER_REFRESH_INTERVAL, R.string.disabled),
				Preferences.MIN_WATCHER_REFRESH_INTERVAL, Preferences.MAX_WATCHER_REFRESH_INTERVAL,
				Preferences.STEP_WATCHER_REFRESH_INTERVAL);
		addCheck(true, Preferences.KEY_BACKGROUND_REPLY_CHECK, Preferences.DEFAULT_BACKGROUND_REPLY_CHECK,
				R.string.background_reply_check, R.string.background_reply_check__summary)
				.setOnAfterChangeListener(p -> BackgroundWatcherWorker.updateSchedule(requireContext()));
		addButton(R.string.reply_notifications, R.string.favorite_reply_shortcut).setOnClickListener(p -> {
			ReplyNotificationsFragment destination = new ReplyNotificationsFragment();
			Bundle arguments = new Bundle();
			PreferenceFragment.putSearchTarget(arguments, null, getString(Preferences.isTrackMyPostsEnabled()
					? R.string.reply_sources : R.string.track_replies));
			destination.setArguments(arguments);
			((FragmentHandler) requireActivity()).pushFragment(destination);
		});
	}
	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.favorites), null);
	}
}
