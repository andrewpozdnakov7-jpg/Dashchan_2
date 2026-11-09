package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.database.HistoryDatabase.Board;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Uses the same form, group headings and choices as CombinedFeedsFragment's feed editor. */
public final class HistoryBoardFilterDialog {
	private HistoryBoardFilterDialog() {}

	/** InstanceDialog retains this draft across rotation; cancel never changes the applied filter. */
	public static final class State {
		private final LinkedHashSet<Board> selected;
		private final LinkedHashSet<String> expanded = new LinkedHashSet<>();
		private boolean initialized;
		private String query = "";

		public State(Collection<Board> selected) {
			this.selected = new LinkedHashSet<>(selected);
		}
	}

	public static String siteTitle(String chanName) {
		Chan chan = Chan.get(chanName);
		String title = chan.name != null ? chan.configuration.getTitle() : null;
		return !StringUtils.isEmptyOrWhitespace(title) ? title : chanName;
	}

	public static String sectionCode(Board board) {
		return "/" + board.boardName + "/";
	}

	private static String sectionTitle(Board board) {
		String title = Chan.get(board.chanName).configuration.getBoardTitle(board.boardName);
		return StringUtils.isEmptyOrWhitespace(title) ? sectionCode(board) : sectionCode(board) + " — " + title;
	}

	@SuppressWarnings("deprecation") // Same IME resize policy as the existing feed editor.
	public static AlertDialog create(Context context, Collection<Board> boards, State state,
			Consumer<Collection<Board>> apply) {
		MotionDialogBuilder builder = MotionDialogBuilder.standard(context);
		Context dialogContext = builder.getContext();
		float density = ResourceUtils.obtainDensity(dialogContext);
		int padding = dialogContext.getResources().getDimensionPixelSize(R.dimen.dialog_padding_view);
		LinearLayout root = new LinearLayout(dialogContext);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(padding, padding, padding, 0);
		EditText search = new SafePasteEditText(dialogContext);
		search.setSingleLine(true);
		search.setHint(R.string.combined_feed_search_boards);
		search.setText(state.query);
		root.addView(search, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		TextView summary = new TextView(dialogContext);
		root.addView(summary, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		InterfaceAppearance.standardCustomInsets(root);
		InterfaceAppearance.standardCustomContent(search);
		InterfaceAppearance.standardCustomCaption(summary);
		Runnable updateSummary = () -> summary.setText(state.selected.isEmpty()
				? dialogContext.getString(R.string.history_all_sections)
				: dialogContext.getString(R.string.history_sections_selected, state.selected.size()));
		updateSummary.run();

		LinkedHashSet<Board> available = new LinkedHashSet<>(boards);
		// A selected section may have lost its last entry while the filter was active. Keep it removable.
		available.addAll(state.selected);
		ArrayList<Board> sorted = new ArrayList<>(available);
		Map<String, String> siteTitles = new LinkedHashMap<>();
		Map<Board, String> labels = new LinkedHashMap<>();
		Map<Board, String> searchText = new LinkedHashMap<>();
		for (Board board : sorted) {
			if (!siteTitles.containsKey(board.chanName)) siteTitles.put(board.chanName, siteTitle(board.chanName));
			String label = sectionTitle(board);
			labels.put(board, label);
			searchText.put(board, (siteTitles.get(board.chanName) + ' ' + board.chanName + ' ' + label)
					.toLowerCase(Locale.ROOT));
		}
		sorted.sort(Comparator.comparing((Board board) -> siteTitles.get(board.chanName), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(board -> board.chanName).thenComparing(board -> board.boardName,
						String.CASE_INSENSITIVE_ORDER));
		LinkedHashMap<String, ArrayList<Board>> groups = new LinkedHashMap<>();
		for (Board board : sorted) {
			ArrayList<Board> group = groups.get(board.chanName);
			if (group == null) {
				group = new ArrayList<>();
				groups.put(board.chanName, group);
			}
			group.add(board);
		}
		if (!state.initialized) {
			for (Board board : state.selected) state.expanded.add(board.chanName);
			if (state.expanded.isEmpty() && !groups.isEmpty()) state.expanded.add(groups.keySet().iterator().next());
			state.initialized = true;
		}
		boolean grouped = groups.size() > 1;
		LinearLayout checks = new LinearLayout(dialogContext);
		checks.setOrientation(LinearLayout.VERTICAL);
		Runnable[] rebuild = new Runnable[1];
		rebuild[0] = () -> {
			checks.removeAllViews();
			String query = state.query.trim().toLowerCase(Locale.ROOT);
			boolean searching = !query.isEmpty();
			int visible = 0;
			for (Map.Entry<String, ArrayList<Board>> entry : groups.entrySet()) {
				String site = siteTitles.get(entry.getKey());
				ArrayList<Board> matches = new ArrayList<>();
				for (Board board : entry.getValue()) {
					if (!searching || searchText.get(board).contains(query)) matches.add(board);
				}
				if (matches.isEmpty()) continue;
				visible += matches.size();
				boolean expanded = !grouped || searching || state.expanded.contains(entry.getKey());
				if (grouped) {
					TextView header = new TextView(dialogContext, null, android.R.attr.textAppearanceListItem);
					header.setText((expanded ? "\u25bc " : "\u25b6 ") + site);
					header.setPadding((int) (8f * density), 0, (int) (8f * density), 0);
					ViewUtils.setSelectableItemBackground(header);
					header.setOnClickListener(v -> {
						if (!state.expanded.remove(entry.getKey())) state.expanded.add(entry.getKey());
						rebuild[0].run();
					});
					checks.addView(header, ViewGroup.LayoutParams.MATCH_PARENT, (int) (48f * density));
					InterfaceAppearance.standardFormHeading(header);
				}
				if (expanded) {
					for (Board board : matches) {
						CheckBox choice = new CheckBox(dialogContext);
						choice.setText(labels.get(board));
						choice.setChecked(state.selected.contains(board));
						choice.setOnCheckedChangeListener((button, checked) -> {
							if (checked) state.selected.add(board);
							else state.selected.remove(board);
							updateSummary.run();
						});
						checks.addView(choice, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
						InterfaceAppearance.standardFormChoice(choice);
					}
				}
			}
			if (visible == 0) {
				TextView empty = new TextView(dialogContext, null, android.R.attr.textAppearanceListItem);
				empty.setText(R.string.combined_feed_no_matching_boards);
				empty.setPadding((int) (8f * density), 0, (int) (8f * density), 0);
				checks.addView(empty, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
				InterfaceAppearance.standardFormHeading(empty);
				InterfaceAppearance.standardCustomCaption(empty);
			}
		};
		search.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) {
				state.query = s.toString();
				rebuild[0].run();
			}
			@Override public void afterTextChanged(Editable s) {}
		});
		rebuild[0].run();
		View content;
		if (InterfaceAppearance.isEnabled(dialogContext)) {
			root.addView(checks, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			DialogFormScrollView form = new DialogFormScrollView(dialogContext);
			form.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			content = form;
		} else {
			ScrollView scroll = new ScrollView(dialogContext);
			scroll.addView(checks, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			root.addView(scroll, ViewGroup.LayoutParams.MATCH_PARENT, (int) (320f * density));
			content = root;
		}
		AlertDialog dialog = builder.setTitle(R.string.history_filter_sections).setView(content)
				.setNegativeButton(android.R.string.cancel, null)
				.setNeutralButton(R.string.history_reset_filter, null)
				.setPositiveButton(R.string.history_apply_filter, (d, which) -> apply.accept(new ArrayList<>(state.selected)))
				.create();
		InterfaceAppearance.standardCustomDialogContent(dialog, content);
		dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
				| (InterfaceAppearance.isEnabled(dialogContext) ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE : 0));
		dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
			state.selected.clear();
			search.setText("");
			updateSummary.run();
			rebuild[0].run();
		}));
		return dialog;
	}
}
