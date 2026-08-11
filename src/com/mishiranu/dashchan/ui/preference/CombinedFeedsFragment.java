package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.annotation.NonNull;
import chan.content.Chan;
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
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class CombinedFeedsFragment extends PreferenceFragment implements GetBoardsTask.Callback,
		ReadBoardsTask.Callback, CombinedFeedStorage.Observer {
	private static final String EXTRA_EDIT_FEED_ID = "editFeedId";

	public static CombinedFeedsFragment forFeed(String feedId) {
		CombinedFeedsFragment fragment = new CombinedFeedsFragment();
		Bundle arguments = new Bundle();
		arguments.putString(EXTRA_EDIT_FEED_ID, feedId);
		fragment.setArguments(arguments);
		return fragment;
	}

	private static class Board {
		public final String name;
		public final String title;

		private Board(String name, String title) {
			this.name = name;
			this.title = title;
		}
	}

	private final ArrayList<Board> boards = new ArrayList<>();
	private GetBoardsTask getBoardsTask;
	private ReadBoardsTask readBoardsTask;
	private boolean boardsLoaded;
	private boolean refreshAttempted;
	private String pendingEditFeedId;

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		if (savedInstanceState == null && getArguments() != null) {
			pendingEditFeedId = getArguments().getString(EXTRA_EDIT_FEED_ID);
		}
		CombinedFeedStorage.getInstance().getObservable().register(this);
		refreshPreferences();
		getBoardsTask = new GetBoardsTask(this, Chan.get("dvach"), null, null);
		getBoardsTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
	}

	private void refreshPreferences() {
		if (getView() == null) {
			return;
		}
		removeAllPreferences();
		Preference<Void> addFeed = addButton(R.string.add_combined_feed, boardsLoaded
				? R.string.combined_feeds_add__summary : R.string.loading__ellipsis);
		addFeed.setEnabled(boardsLoaded);
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

	private static String formatSources(List<CombinedFeedStorage.Source> sources) {
		StringBuilder builder = new StringBuilder();
		for (CombinedFeedStorage.Source source : sources) {
			if (builder.length() > 0) {
				builder.append(", ");
			}
			builder.append('/').append(source.boardName).append('/');
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
		CheckBox showSticky = new CheckBox(requireContext());
		showSticky.setText(R.string.show_sticky_threads);
		showSticky.setChecked(existing == null || existing.showSticky);
		root.addView(showSticky, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

		HashSet<String> selected = new HashSet<>();
		if (existing != null) {
			for (CombinedFeedStorage.Source source : existing.sources) {
				if ("dvach".equals(source.chanName)) {
					selected.add(source.boardName);
				}
			}
		}
		LinearLayout checks = new LinearLayout(requireContext());
		checks.setOrientation(LinearLayout.VERTICAL);
		ArrayList<CheckBox> checkBoxes = new ArrayList<>(boards.size());
		for (Board board : boards) {
			CheckBox checkBox = new CheckBox(requireContext());
			checkBox.setText(board.title);
			checkBox.setTag(board.name);
			checkBox.setChecked(selected.contains(board.name));
			checks.addView(checkBox, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			checkBoxes.add(checkBox);
		}
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
				ArrayList<String> selectedBoards = new ArrayList<>();
				for (CheckBox checkBox : checkBoxes) {
					if (checkBox.isChecked()) {
						selectedBoards.add((String) checkBox.getTag());
					}
				}
				if (StringUtils.isEmptyOrWhitespace(title)) {
					ClickableToast.show(R.string.combined_feed_name_required);
					return;
				}
				if (selectedBoards.size() < 2) {
					ClickableToast.show(R.string.combined_feed_minimum_boards);
					return;
				}
				CombinedFeedStorage.Feed feed = new CombinedFeedStorage.Feed();
				feed.id = existing != null ? existing.id : null;
				feed.title = title;
				feed.showSticky = showSticky.isChecked();
				for (String boardName : selectedBoards) {
					feed.sources.add(new CombinedFeedStorage.Source("dvach", boardName));
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

	@Override
	public void onGetBoardsResult(ChanDatabase.BoardCursor cursor) {
		getBoardsTask = null;
		boards.clear();
		if (cursor != null) {
			try {
				if (cursor.moveToFirst()) {
					ChanDatabase.BoardItem item = new ChanDatabase.BoardItem();
					do {
						item.update(cursor);
						boards.add(new Board(item.boardName,
								StringUtils.formatBoardTitle("", item.boardName, item.extra1)));
					} while (cursor.moveToNext());
				}
			} finally {
				cursor.close();
			}
		}
		Collections.sort(boards, (first, second) ->
				String.CASE_INSENSITIVE_ORDER.compare(first.title, second.title));
		boardsLoaded = !boards.isEmpty();
		if (!boardsLoaded && !refreshAttempted) {
			refreshAttempted = true;
			readBoardsTask = new ReadBoardsTask(this, Chan.get("dvach"));
			readBoardsTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
			return;
		}
		refreshPreferences();
		openPendingFeedIfReady();
		if (!boardsLoaded && readBoardsTask == null) {
			ClickableToast.show(R.string.combined_feeds_boards_unavailable);
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
		getBoardsTask = new GetBoardsTask(this, Chan.get("dvach"), null, null);
		getBoardsTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
	}

	@Override
	public void onReadBoardsFail(com.mishiranu.dashchan.content.model.ErrorItem errorItem) {
		readBoardsTask = null;
		refreshPreferences();
		ClickableToast.show(errorItem);
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
