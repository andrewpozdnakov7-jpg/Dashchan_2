package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

	private static class Board {
		public final String chanName;
		public final String chanTitle;
		public final String name;
		public final String title;
		public final boolean unavailable;

		private Board(String chanName, String chanTitle, String name, String title, boolean unavailable) {
			this.chanName = chanName;
			this.chanTitle = chanTitle;
			this.name = name;
			this.title = title;
			this.unavailable = unavailable;
		}

		private String getKey() {
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

	private static String makeSourceKey(String chanName, String boardName) {
		return chanName + '\n' + boardName;
	}

	private static String getChanTitle(String chanName) {
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

	private static String formatSourceLabel(String chanName, String boardName) {
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
		float density = ResourceUtils.obtainDensity(requireContext());
		int padding = getResources().getDimensionPixelSize(R.dimen.dialog_padding_view);
		LinearLayout root = new LinearLayout(requireContext());
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(padding, padding, padding, 0);
		EditText name = new SafePasteEditText(requireContext());
		name.setSingleLine(true);
		name.setHint(R.string.combined_feed_name);
		name.setText(existing != null ? existing.title : "");
		name.setSelection(name.length());
		root.addView(name, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

		EditText search = new SafePasteEditText(requireContext());
		search.setSingleLine(true);
		search.setHint(R.string.combined_feed_search_boards);
		root.addView(search, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		CheckBox showSticky = new CheckBox(requireContext());
		showSticky.setText(R.string.show_sticky_threads);
		showSticky.setChecked(existing == null || existing.showSticky);
		root.addView(showSticky, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		CheckBox showBoard = new CheckBox(requireContext());
		showBoard.setText(R.string.combined_feed_show_board);
		showBoard.setChecked(existing == null || existing.showBoard);
		root.addView(showBoard, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

		HashSet<String> selected = new HashSet<>();
		if (existing != null) {
			for (CombinedFeedStorage.Source source : existing.sources) {
				selected.add(makeSourceKey(source.chanName, source.boardName));
			}
		}
		ArrayList<Board> dialogBoards = new ArrayList<>(boards);
		HashSet<String> availableKeys = new HashSet<>();
		for (Board board : boards) {
			availableKeys.add(board.getKey());
		}
		if (existing != null) {
			for (CombinedFeedStorage.Source source : existing.sources) {
				String key = makeSourceKey(source.chanName, source.boardName);
				if (!availableKeys.contains(key)) {
					dialogBoards.add(new Board(source.chanName, getChanTitle(source.chanName), source.boardName,
							formatSourceLabel(source.chanName, source.boardName), true));
				}
			}
		}

		LinkedHashMap<String, ArrayList<Board>> groupedBoards = new LinkedHashMap<>();
		for (Board board : dialogBoards) {
			String group = board.unavailable ? UNAVAILABLE_GROUP : board.chanName;
			ArrayList<Board> groupBoards = groupedBoards.get(group);
			if (groupBoards == null) {
				groupBoards = new ArrayList<>();
				groupedBoards.put(group, groupBoards);
			}
			groupBoards.add(board);
		}
		boolean grouped = loadingChans.size() > 1 || groupedBoards.containsKey(UNAVAILABLE_GROUP);
		HashSet<String> expandedGroups = new HashSet<>();
		if (grouped) {
			for (Board board : dialogBoards) {
				if (selected.contains(board.getKey())) {
					expandedGroups.add(board.unavailable ? UNAVAILABLE_GROUP : board.chanName);
				}
			}
			if (expandedGroups.isEmpty() && !groupedBoards.isEmpty()) {
				expandedGroups.add(groupedBoards.keySet().iterator().next());
			}
		}

		LinearLayout checks = new LinearLayout(requireContext());
		checks.setOrientation(LinearLayout.VERTICAL);
		Runnable[] rebuild = new Runnable[1];
		rebuild[0] = () -> {
			checks.removeAllViews();
			String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
			boolean searching = !query.isEmpty();
			int visibleBoards = 0;
			for (Map.Entry<String, ArrayList<Board>> entry : groupedBoards.entrySet()) {
				ArrayList<Board> matches = new ArrayList<>();
				for (Board board : entry.getValue()) {
					String haystack = (board.chanTitle + ' ' + board.chanName + ' ' + board.name + ' '
							+ board.title).toLowerCase(Locale.ROOT);
					if (!searching || haystack.contains(query)) {
						matches.add(board);
					}
				}
				if (matches.isEmpty()) {
					continue;
				}
				visibleBoards += matches.size();
				boolean expanded = !grouped || searching || expandedGroups.contains(entry.getKey());
				if (grouped) {
					String groupTitle = UNAVAILABLE_GROUP.equals(entry.getKey())
							? getString(R.string.combined_feed_unavailable_boards) : matches.get(0).chanTitle;
					TextView header = new TextView(requireContext(), null, android.R.attr.textAppearanceListItem);
					header.setText((expanded ? "\u25bc " : "\u25b6 ") + groupTitle);
					header.setPadding((int) (8f * density), 0, (int) (8f * density), 0);
					ViewUtils.setSelectableItemBackground(header);
					header.setOnClickListener(v -> {
						if (expandedGroups.contains(entry.getKey())) {
							expandedGroups.remove(entry.getKey());
						} else {
							expandedGroups.add(entry.getKey());
						}
						rebuild[0].run();
					});
					checks.addView(header, ViewGroup.LayoutParams.MATCH_PARENT, (int) (48f * density));
				}
				if (expanded) {
					for (Board board : matches) {
						CheckBox checkBox = new CheckBox(requireContext());
						checkBox.setText(board.title);
						checkBox.setChecked(selected.contains(board.getKey()));
						checkBox.setOnCheckedChangeListener((button, checked) -> {
							if (checked) {
								selected.add(board.getKey());
							} else {
								selected.remove(board.getKey());
							}
						});
						checks.addView(checkBox, ViewGroup.LayoutParams.MATCH_PARENT,
								ViewGroup.LayoutParams.WRAP_CONTENT);
					}
				}
			}
			if (visibleBoards == 0) {
				TextView empty = new TextView(requireContext(), null, android.R.attr.textAppearanceListItem);
				empty.setText(R.string.combined_feed_no_matching_boards);
				empty.setPadding((int) (8f * density), 0, (int) (8f * density), 0);
				checks.addView(empty, ViewGroup.LayoutParams.MATCH_PARENT, (int) (48f * density));
			}
		};
		search.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				rebuild[0].run();
			}

			@Override
			public void afterTextChanged(Editable s) {}
		});
		rebuild[0].run();
		ScrollView scrollView = new ScrollView(requireContext());
		scrollView.addView(checks, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		root.addView(scrollView, ViewGroup.LayoutParams.MATCH_PARENT, (int) (320f * density));

		AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
				.setTitle(existing != null ? R.string.edit_combined_feed : R.string.add_combined_feed)
				.setView(root)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, null);
		if (existing != null) {
			builder.setNeutralButton(R.string.delete, null);
		}
		AlertDialog dialog = builder.create();
		dialog.setOnShowListener(ignored -> {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
				String title = name.getText().toString().trim();
				if (StringUtils.isEmptyOrWhitespace(title)) {
					ClickableToast.show(R.string.combined_feed_name_required);
					return;
				}
				if (selected.size() < 2) {
					ClickableToast.show(R.string.combined_feed_minimum_boards);
					return;
				}
				CombinedFeedStorage.Feed feed = new CombinedFeedStorage.Feed();
				feed.id = existing != null ? existing.id : null;
				feed.title = title;
				feed.showSticky = showSticky.isChecked();
				feed.showBoard = showBoard.isChecked();
				for (Board board : dialogBoards) {
					if (selected.contains(board.getKey())) {
						feed.sources.add(new CombinedFeedStorage.Source(board.chanName, board.name));
					}
				}
				if (CombinedFeedStorage.getInstance().put(feed)) {
					dialog.dismiss();
				} else {
					ClickableToast.show(R.string.combined_feeds_limit);
				}
			});
			if (existing != null) {
				dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button ->
						new AlertDialog.Builder(requireContext())
								.setMessage(R.string.delete_combined_feed_confirmation)
								.setNegativeButton(android.R.string.cancel, null)
								.setPositiveButton(R.string.delete, (confirmation, which) -> {
									CombinedFeedStorage.getInstance().remove(existing.id);
									dialog.dismiss();
								}).show());
			}
		});
		dialog.show();
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
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.combined_feeds), null);
	}
}
