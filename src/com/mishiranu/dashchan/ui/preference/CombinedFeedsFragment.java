package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import chan.content.Chan;
import chan.content.ChanManager;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.GetBoardsTask;
import com.mishiranu.dashchan.content.async.ReadBoardsTask;
import com.mishiranu.dashchan.content.database.ChanDatabase;
import com.mishiranu.dashchan.content.storage.CombinedFeedStorage;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.util.ArrayList;
import java.util.List;

public class CombinedFeedsFragment extends PreferenceFragment implements GetBoardsTask.Callback,
		ReadBoardsTask.Callback, CombinedFeedStorage.Observer {
	private static final String EXTRA_EDIT_FEED_ID = "editFeedId";
	private static final String UNAVAILABLE_GROUP = "\nunavailable";

	public static CombinedFeedsFragment forFeed(String feedId) {
		CombinedFeedsFragment fragment = new CombinedFeedsFragment();
		Bundle arguments = new Bundle();
		arguments.putString(EXTRA_EDIT_FEED_ID, feedId);
		fragment.setArguments(arguments);
		return fragment;
	}

	static final class Board {
		public final String chanName;
		public final String chanTitle;
		public final String name;
		public final String title;
		public final boolean unavailable;

		Board(String chanName, String chanTitle, String name, String title, boolean unavailable) {
			this.chanName = chanName;
			this.chanTitle = chanTitle;
			this.name = name;
			this.title = title;
			this.unavailable = unavailable;
		}

		String getKey() {
			return makeSourceKey(chanName, name);
		}
	}

	private final ArrayList<Board> boards = new ArrayList<>();
	private final ArrayList<Chan> loadingChans = new ArrayList<>();
	private GetBoardsTask getBoardsTask;
	private ReadBoardsTask readBoardsTask;
	private int loadingChanIndex;
	private Chan loadingChan;
	private boolean refreshAttempted;
	private boolean boardsLoaded;
	private String pendingEditFeedId;

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		boards.clear();
		loadingChans.clear();
		loadingChanIndex = 0;
		loadingChan = null;
		refreshAttempted = false;
		boardsLoaded = false;
		if (savedInstanceState == null && getArguments() != null) {
			pendingEditFeedId = getArguments().getString(EXTRA_EDIT_FEED_ID);
		}
		CombinedFeedStorage.getInstance().getObservable().register(this);
		refreshPreferences();
		for (Chan chan : ChanManager.getInstance().getAvailableChans()) {
			loadingChans.add(chan);
		}
		loadNextChan();
	}

	private void refreshPreferences() {
		if (getView() == null) {
			return;
		}
		removeAllPreferences();
		Preference<Void> addFeed = addButton(R.string.add_combined_feed, boardsLoaded
				? R.string.combined_feeds_add__summary : R.string.loading__ellipsis);
		addFeed.setEnabled(boardsLoaded && !boards.isEmpty());
		addFeed.setOnClickListener(p -> showFeedDialog(null));
		List<CombinedFeedStorage.Feed> feeds = CombinedFeedStorage.getInstance().getFeeds();
		if (feeds.isEmpty()) {
			addHeader(R.string.combined_feeds_empty);
		} else {
			addHeader(R.string.combined_feeds);
			for (CombinedFeedStorage.Feed feed : feeds) {
				addButton(feed.title, formatSources(feed.sources)).setOnClickListener(p -> showFeedDialog(feed));
			}
		}
	}

	static String makeSourceKey(String chanName, String boardName) {
		return chanName + '\n' + boardName;
	}

	static String getChanTitle(String chanName) {
		if ("dvach".equals(chanName)) {
			return "2ch";
		}
		Chan chan = Chan.get(chanName);
		String title = chan.name != null ? chan.configuration.getTitle() : null;
		if (StringUtils.isEmptyOrWhitespace(title)) {
			title = chanName;
		}
		if (title.indexOf(' ') < 0) {
			int dot = title.indexOf('.');
			if (dot > 0) {
				title = title.substring(0, dot);
			}
		}
		return title;
	}

	static String formatSourceLabel(String chanName, String boardName) {
		return getChanTitle(chanName) + " /" + boardName + "/";
	}

	private static String formatSources(List<CombinedFeedStorage.Source> sources) {
		StringBuilder builder = new StringBuilder();
		for (CombinedFeedStorage.Source source : sources) {
			if (builder.length() > 0) {
				builder.append(", ");
			}
			builder.append(formatSourceLabel(source.chanName, source.boardName));
		}
		return builder.toString();
	}

	private void showFeedDialog(CombinedFeedStorage.Feed existing) {
		if (!boardsLoaded) {
			ClickableToast.show(R.string.loading__ellipsis);
			return;
		}
		CombinedFeedDialog.State draft = new CombinedFeedDialog.State(existing, new ArrayList<>(boards),
				loadingChans.size() > 1);
		new InstanceDialog(getChildFragmentManager(), CombinedFeedDialog.class.getName(),
				provider -> CombinedFeedDialog.create(provider, draft));
	}

	private void loadNextChan() {
		loadingChan = null;
		refreshAttempted = false;
		if (loadingChanIndex < loadingChans.size()) {
			loadingChan = loadingChans.get(loadingChanIndex++);
			getBoardsTask = new GetBoardsTask(this, loadingChan, null, null);
			getBoardsTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
		} else {
			boardsLoaded = true;
			refreshPreferences();
			openPendingFeedIfReady();
			if (boards.isEmpty()) {
				ClickableToast.show(R.string.combined_feeds_boards_unavailable);
			}
		}
	}

	@Override
	public void onGetBoardsResult(ChanDatabase.BoardCursor cursor) {
		getBoardsTask = null;
		ArrayList<Board> loadedBoards = new ArrayList<>();
		if (cursor != null && loadingChan != null) {
			try {
				if (cursor.moveToFirst()) {
					ChanDatabase.BoardItem item = new ChanDatabase.BoardItem();
					do {
						item.update(cursor);
						loadedBoards.add(new Board(loadingChan.name, getChanTitle(loadingChan.name), item.boardName,
								loadingChan.configuration.formatBoardTitle(item.boardName, item.extra1), false));
					} while (cursor.moveToNext());
				}
			} finally {
				cursor.close();
			}
		}
		loadedBoards.sort((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.title, second.title));
		boards.addAll(loadedBoards);
		if (loadedBoards.isEmpty() && !refreshAttempted && loadingChan != null) {
			refreshAttempted = true;
			readBoardsTask = new ReadBoardsTask(this, loadingChan);
			readBoardsTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
		} else {
			loadNextChan();
		}
	}

	private void openPendingFeedIfReady() {
		if (!boardsLoaded || StringUtils.isEmpty(pendingEditFeedId)) {
			return;
		}
		String feedId = pendingEditFeedId;
		pendingEditFeedId = null;
		CombinedFeedStorage.Feed feed = CombinedFeedStorage.getInstance().getFeed(feedId);
		if (feed != null) {
			showFeedDialog(feed);
		} else {
			ClickableToast.show(R.string.combined_feed_not_found);
		}
	}

	@Override
	public void onReadBoardsSuccess() {
		readBoardsTask = null;
		if (loadingChan != null) {
			getBoardsTask = new GetBoardsTask(this, loadingChan, null, null);
			getBoardsTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
		} else {
			loadNextChan();
		}
	}

	@Override
	public void onReadBoardsFail(com.mishiranu.dashchan.content.model.ErrorItem errorItem) {
		readBoardsTask = null;
		loadNextChan();
	}

	@Override
	public void onCombinedFeedsChanged() {
		refreshPreferences();
	}

	@Override
	public void onDestroyView() {
		if (getBoardsTask != null) {
			getBoardsTask.cancel();
			getBoardsTask = null;
		}
		if (readBoardsTask != null) {
			readBoardsTask.cancel();
			readBoardsTask = null;
		}
		CombinedFeedStorage.getInstance().getObservable().unregister(this);
		super.onDestroyView();
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.combined_feeds), null);
	}
}
