package com.mishiranu.dashchan.ui.navigator.page;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.GetHistoryTask;
import com.mishiranu.dashchan.content.database.CommonDatabase;
import com.mishiranu.dashchan.content.database.HistoryDatabase;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.ui.DialogMenu;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.HistoryRemoveSwipeController;
import com.mishiranu.dashchan.ui.navigator.adapter.HistoryAdapter;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.HeaderItemDecoration;
import com.mishiranu.dashchan.widget.HistoryBoardFilterDialog;
import com.mishiranu.dashchan.widget.HistoryTextAppearance;
import com.mishiranu.dashchan.widget.ListPosition;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;

public class HistoryPage extends ListPage implements HistoryAdapter.Callback, GetHistoryTask.Callback {
	private static class ParcelableExtra implements Parcelable {
		public static final ExtraFactory<ParcelableExtra> FACTORY = ParcelableExtra::new;
		public final ArrayList<HistoryDatabase.Board> selectedBoards = new ArrayList<>();

		@Override public int describeContents() { return 0; }

		@Override public void writeToParcel(Parcel dest, int flags) {
			dest.writeInt(selectedBoards.size());
			for (HistoryDatabase.Board board : selectedBoards) {
				dest.writeString(board.chanName);
				dest.writeString(board.boardName);
			}
		}

		public static final Creator<ParcelableExtra> CREATOR = new Creator<ParcelableExtra>() {
			@Override public ParcelableExtra createFromParcel(Parcel source) {
				ParcelableExtra extra = new ParcelableExtra();
				int count = source.readInt();
				for (int i = 0; i < count; i++) {
					extra.selectedBoards.add(new HistoryDatabase.Board(source.readString(), source.readString()));
				}
				return extra;
			}
			@Override public ParcelableExtra[] newArray(int size) { return new ParcelableExtra[size]; }
		};
	}

	private String chanName;
	private String searchQuery;
	private ParcelableExtra extra;
	private boolean resetPosition;

	private GetHistoryTask task;
	private boolean firstLoad = true;
	private HistoryRemoveSwipeController removeSwipe;

	private HistoryAdapter getAdapter() {
		return (HistoryAdapter) getRecyclerView().getAdapter();
	}

	@Override
	protected void onCreate() {
		PaddedRecyclerView recyclerView = getRecyclerView();
		recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
		chanName = Preferences.isMergeChans() ? null : getPage().chanName;
		extra = getParcelableExtra(ParcelableExtra.FACTORY);
		if (chanName != null) extra.selectedBoards.removeIf(board -> !chanName.equals(board.chanName));
		searchQuery = getInitSearch().currentQuery;
		CommonDatabase.getInstance().getHistory().registerObserver(updateHistoryRunnable);
		HistoryAdapter adapter = new HistoryAdapter(getContext(), this, chanName);
		recyclerView.setAdapter(adapter);
		recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(),
				adapter::configureDivider));
		recyclerView.addItemDecoration(new HeaderItemDecoration(
				(context, header) -> HistoryTextAppearance.header(header), adapter::getItemHeader, false, true));
		recyclerView.setItemAnimator(null);
		removeSwipe = new HistoryRemoveSwipeController(recyclerView, adapter, this::removeHistoryItem);
		switchProgress();
		updateHistory();
	}

	@Override
	protected void onDestroy() {
		if (removeSwipe != null) { removeSwipe.close(); removeSwipe = null; }
		CommonDatabase.getInstance().getHistory().unregisterObserver(updateHistoryRunnable);
		getAdapter().setCursor(null);
		if (task != null) {
			task.cancel();
			task = null;
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (removeSwipe != null) removeSwipe.resume();
	}

	@Override
	protected void onPause() {
		if (removeSwipe != null) removeSwipe.pause();
		super.onPause();
	}

	private void removeHistoryItem(HistoryDatabase.HistoryItem item) {
		// Prevent a query started before removal from putting the outgoing row back on screen.
		if (task != null) { task.cancel(); task = null; }
		CommonDatabase.getInstance().getHistory().remove(item.chanName, item.boardName, item.threadNumber);
	}

	@Override
	public String obtainTitle() {
		return getString(R.string.history);
	}

	@Override
	public Pair<String, String> obtainTitleSubtitle() {
		String subtitle = null;
		if (extra != null && !extra.selectedBoards.isEmpty()) {
			if (extra.selectedBoards.size() <= 2) {
				StringBuilder names = new StringBuilder();
				for (HistoryDatabase.Board board : extra.selectedBoards) {
					if (names.length() > 0) names.append(", ");
					if (chanName == null) names.append(HistoryBoardFilterDialog.siteTitle(board.chanName)).append(' ');
					names.append(HistoryBoardFilterDialog.sectionCode(board));
				}
				subtitle = getString(R.string.history_sections_summary, names);
			} else {
				subtitle = getString(R.string.history_sections_selected, extra.selectedBoards.size());
			}
		}
		return new Pair<>(obtainTitle(), subtitle);
	}

	@Override
	public void onItemClick(HistoryDatabase.HistoryItem historyItem) {
		getUiManager().navigator().navigatePosts(historyItem.chanName, historyItem.boardName,
				historyItem.threadNumber, null, null);
	}

	@Override
	public boolean onItemLongClick(HistoryDatabase.HistoryItem historyItem) {
		showItemPopupMenu(getFragmentManager(), historyItem);
		return true;
	}

	private void showItemPopupMenu(FragmentManager fragmentManager, HistoryDatabase.HistoryItem historyItem) {
		new InstanceDialog(fragmentManager, null, provider -> {
			DialogMenu dialogMenu = new DialogMenu(provider.getContext());
			dialogMenu.add(R.string.copy_link, () -> {
				Uri uri = Chan.get(historyItem.chanName).locator.safe(true)
						.createThreadUri(historyItem.boardName, historyItem.threadNumber);
				if (uri != null) {
					StringUtils.copyToClipboard(getContext(), uri.toString());
				}
			});
			if (!FavoritesStorage.getInstance().hasFavorite(historyItem.chanName,
					historyItem.boardName, historyItem.threadNumber)) {
				dialogMenu.add(R.string.add_to_favorites, () -> FavoritesStorage.getInstance()
						.add(historyItem.chanName, historyItem.boardName, historyItem.threadNumber,
								historyItem.title, true));
			}
			dialogMenu.add(R.string.remove_from_history, () -> CommonDatabase.getInstance().getHistory()
					.remove(historyItem.chanName, historyItem.boardName, historyItem.threadNumber));
			return dialogMenu.create();
		});
	}

	@Override
	public void onCreateOptionsMenu(Menu menu) {
		menu.add(0, R.id.menu_search, 0, R.string.search)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
		menu.add(0, R.id.menu_filter, 0, R.string.history_filter_sections);
		menu.add(0, R.id.menu_clear, 0, R.string.clear_history);
		menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.menu_filter) {
			showSectionFilter();
			return true;
		}
		if (item.getItemId() == R.id.menu_clear) {
			showClearHistoryDialog(getFragmentManager(), chanName);
			return true;
		}
		return false;
	}

	private void showSectionFilter() {
		HistoryDatabase.HistoryCursor cursor = getAdapter().getCursor();
		if (cursor == null) {
			ClickableToast.show(R.string.loading__ellipsis);
			return;
		}
		ArrayList<HistoryDatabase.Board> boards = new ArrayList<>(cursor.boards);
		HistoryBoardFilterDialog.State draft = new HistoryBoardFilterDialog.State(extra.selectedBoards);
		new InstanceDialog(getFragmentManager(), HistoryBoardFilterDialog.class.getName(), provider ->
				HistoryBoardFilterDialog.create(provider.getContext(), boards, draft, selected -> {
					// Resolve the current page after rotation; never retain the old page's views in a dialog.
					ListPage page = extract(provider);
					if (page instanceof HistoryPage) ((HistoryPage) page).applySectionFilter(selected);
				}));
	}

	private void applySectionFilter(Collection<HistoryDatabase.Board> selected) {
		if (removeSwipe != null) removeSwipe.cancelPresentation();
		extra.selectedBoards.clear();
		extra.selectedBoards.addAll(new LinkedHashSet<>(selected));
		resetPosition = true;
		notifyTitleChanged();
		updateHistory();
	}

	private static void showClearHistoryDialog(FragmentManager fragmentManager, String chanName) {
		new InstanceDialog(fragmentManager, null, provider -> MotionDialogBuilder.standard(provider.getContext(), true)
				.setMessage(chanName == null ? provider.getContext().getString(R.string.history_clear_all_scope)
						: provider.getContext().getString(R.string.history_clear_site_scope,
								HistoryBoardFilterDialog.siteTitle(chanName)))
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, (d, w) -> CommonDatabase
						.getInstance().getHistory().clearHistory(chanName))
				.create());
	}

	@Override
	public void onSearchQueryChange(String query) {
		if (removeSwipe != null) removeSwipe.cancelPresentation();
		searchQuery = query;
		updateHistory();
	}

	private final Runnable updateHistoryRunnable = this::updateHistory;

	private void updateHistory() {
		if (task != null) {
			task.cancel();
		}
		task = new GetHistoryTask(this, chanName, searchQuery, extra.selectedBoards);
		task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
	}

	@Override
	public void onGetHistoryResult(HistoryDatabase.HistoryCursor cursor) {
		task = null;
		boolean firstLoad = this.firstLoad;
		this.firstLoad = false;
		getAdapter().setCursor(cursor);
		ListPosition listPosition = takeListPosition();
		if (cursor.getCount() > 0) {
			switchList();
			if (resetPosition) {
				getRecyclerView().scrollToPosition(0);
			} else if (firstLoad && listPosition != null) {
				listPosition.apply(getRecyclerView());
			}
		} else {
			switchError(cursor.hasItems && cursor.filtered ? R.string.history_no_matches : R.string.history_is_empty);
		}
		resetPosition = false;
	}
}
