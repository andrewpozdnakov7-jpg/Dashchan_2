package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.storage.CombinedFeedStorage;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.preference.CombinedFeedsFragment.Board;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DialogFormInsets;
import com.mishiranu.dashchan.widget.DialogFormScrollView;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import static com.mishiranu.dashchan.ui.preference.CombinedFeedsFragment.makeSourceKey;
import static com.mishiranu.dashchan.ui.preference.CombinedFeedsFragment.getChanTitle;
import static com.mishiranu.dashchan.ui.preference.CombinedFeedsFragment.formatSourceLabel;

/** InstanceDialog factory captures this values-only draft, never the previous Activity or views. */
final class CombinedFeedDialog {
	private CombinedFeedDialog() {}
	private static final String UNAVAILABLE_GROUP = "\nunavailable";
	static final class State {
		final CombinedFeedStorage.Feed existing;
		final ArrayList<Board> boards;
		final boolean multipleSites;
		final HashSet<String> selected = new HashSet<>(), expandedGroups = new HashSet<>();
		final DialogFormScrollView.State viewport = new DialogFormScrollView.State();
		String name, query = "";
		boolean showSticky, showBoard, groupsInitialized;
		State(CombinedFeedStorage.Feed feed, ArrayList<Board> boards, boolean multipleSites) {
			existing = feed != null ? new CombinedFeedStorage.Feed(feed) : null;
			this.boards = new ArrayList<>(boards); this.multipleSites = multipleSites;
			name = feed != null ? feed.title : "";
			showSticky = feed == null || feed.showSticky; showBoard = feed == null || feed.showBoard;
			if (feed != null) for (CombinedFeedStorage.Source source : feed.sources) selected.add(makeSourceKey(source.chanName, source.boardName));
		}
	}
	static AlertDialog create(InstanceDialog.Provider provider, State state) {
		MotionDialogBuilder builder = MotionDialogBuilder.standard(provider.getContext());
		Context context = builder.getContext();
		CombinedFeedStorage.Feed existing = state.existing;
		ArrayList<Board> boards = state.boards;
		float density = ResourceUtils.obtainDensity(context);
		int padding = context.getResources().getDimensionPixelSize(R.dimen.dialog_padding_view);
		LinearLayout root = new LinearLayout(context);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(padding, padding, padding, 0);
		EditText name = new SafePasteEditText(context);
		name.setSingleLine(true);
		name.setId(R.id.combined_feed_dialog_name);
		name.setHint(R.string.combined_feed_name);
		name.setText(state.name);
		name.setSelection(name.length());
		root.addView(name, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

		EditText search = new SafePasteEditText(context);
		search.setSingleLine(true);
		search.setId(R.id.combined_feed_dialog_search);
		search.setText(state.query);
		search.setHint(R.string.combined_feed_search_boards);
		root.addView(search, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		CheckBox showSticky = new CheckBox(context);
		showSticky.setText(R.string.show_sticky_threads);
		showSticky.setChecked(state.showSticky);
		showSticky.setOnCheckedChangeListener((button, checked) -> state.showSticky = checked);
		root.addView(showSticky, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		CheckBox showBoard = new CheckBox(context);
		showBoard.setText(R.string.combined_feed_show_board);
		showBoard.setChecked(state.showBoard);
		showBoard.setOnCheckedChangeListener((button, checked) -> state.showBoard = checked);
		root.addView(showBoard, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		InterfaceAppearance.standardCustomInsets(root);
		InterfaceAppearance.standardCustomContent(name);
		InterfaceAppearance.standardCustomContent(search);
		InterfaceAppearance.standardFormChoice(showSticky);
		InterfaceAppearance.standardFormChoice(showBoard);

		HashSet<String> selected = state.selected;
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
		boolean grouped = state.multipleSites || groupedBoards.containsKey(UNAVAILABLE_GROUP);
		HashSet<String> expandedGroups = state.expandedGroups;
		if (grouped && !state.groupsInitialized) {
			state.groupsInitialized = true;
			for (Board board : dialogBoards) {
				if (selected.contains(board.getKey())) {
					expandedGroups.add(board.unavailable ? UNAVAILABLE_GROUP : board.chanName);
				}
			}
			if (expandedGroups.isEmpty() && !groupedBoards.isEmpty()) {
				expandedGroups.add(groupedBoards.keySet().iterator().next());
			}
		}

		LinearLayout checks = new LinearLayout(context);
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
							? context.getString(R.string.combined_feed_unavailable_boards) : matches.get(0).chanTitle;
					TextView header = new TextView(context, null, android.R.attr.textAppearanceListItem);
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
					InterfaceAppearance.standardFormHeading(header);
				}
				if (expanded) {
					for (Board board : matches) {
						CheckBox checkBox = new CheckBox(context);
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
						InterfaceAppearance.standardFormChoice(checkBox);
					}
				}
			}
			if (visibleBoards == 0) {
				TextView empty = new TextView(context, null, android.R.attr.textAppearanceListItem);
				empty.setText(R.string.combined_feed_no_matching_boards);
				empty.setPadding((int) (8f * density), 0, (int) (8f * density), 0);
				checks.addView(empty, ViewGroup.LayoutParams.MATCH_PARENT, (int) (48f * density));
				InterfaceAppearance.standardFormHeading(empty);
				InterfaceAppearance.standardCustomCaption(empty);
			}
		};
		name.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) { state.name = s.toString(); }
			@Override public void afterTextChanged(Editable s) {}
		});
		search.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				state.query = s.toString();
				rebuild[0].run();
			}

			@Override
			public void afterTextChanged(Editable s) {}
		});
		rebuild[0].run();
		DialogFormScrollView form = DialogFormScrollView.create(context, root, checks, InterfaceAppearance.isEnabled(context));
		form.retain(state.viewport, provider.getLifecycleOwner(), name, search);
		View dialogContent = form.dialogContent();

		builder.setTitle(existing != null ? R.string.edit_combined_feed : R.string.add_combined_feed)
				.setView(dialogContent)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, null);
		if (existing != null) {
			builder.setNeutralButton(R.string.delete, null);
		}
		AlertDialog dialog = builder.create();
		InterfaceAppearance.standardCustomDialogContent(dialog, dialogContent);
		DialogFormInsets.configure(dialog);
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
					provider.dismiss();
				} else {
					ClickableToast.show(R.string.combined_feeds_limit);
				}
			});
			if (existing != null) {
				dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button -> showDeleteConfirmation(provider, existing.id));
			}
		});
		return dialog;
	}

	private static void showDeleteConfirmation(InstanceDialog.Provider editor, String feedId) {
		new InstanceDialog(editor.getFragmentManager(), CombinedFeedDialog.class.getName() + ".delete", current ->
				MotionDialogBuilder.standard(current.getContext())
						.setMessage(R.string.delete_combined_feed_confirmation)
						.setNegativeButton(android.R.string.cancel, null)
						.setPositiveButton(R.string.delete, (confirmation, which) -> {
							CombinedFeedStorage.getInstance().remove(feedId);
							Fragment target = current.getFragmentManager().findFragmentByTag(CombinedFeedDialog.class.getName());
							if (target instanceof DialogFragment) ((DialogFragment) target).dismiss();
						}).create());
	}
}
