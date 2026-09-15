package com.mishiranu.dashchan.ui.navigator.page;

import android.app.AlertDialog;
import android.net.Uri;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.chan.pikabu.PikabuChanConfiguration;
import com.mishiranu.dashchan.chan.pikabu.PikabuChanLocator;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.GetBoardsTask;
import com.mishiranu.dashchan.content.async.ReadBoardsTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.content.database.ChanDatabase;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.ui.DialogMenu;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.navigator.adapter.BoardsAdapter;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.HeaderItemDecoration;
import com.mishiranu.dashchan.widget.ListPosition;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import com.mishiranu.dashchan.widget.PullableWrapper;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import java.util.Set;

public class BoardsPage extends ListPage implements BoardsAdapter.Callback,
		GetBoardsTask.Callback, ReadBoardsTask.Callback {
	private static final String EXPANDED_CATEGORIES_INITIALIZED = "__initialized__";
	private static class RetainableExtra implements Retainable {
		public static final ExtraFactory<RetainableExtra> FACTORY = RetainableExtra::new;

		public boolean firstLoad = true;
		public boolean pikabuCatalogRefreshAttempted;
	}

	public static class ReadViewModel extends TaskViewModel.Proxy<ReadBoardsTask, ReadBoardsTask.Callback> {}

	private String searchQuery;
	private Set<String> expandedCategories;
	private boolean pikabuBoards;

	private GetBoardsTask getTask;

	private BoardsAdapter getAdapter() {
		return (BoardsAdapter) getRecyclerView().getAdapter();
	}

	@Override
	protected void onCreate() {
		PaddedRecyclerView recyclerView = getRecyclerView();
		recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
		searchQuery = getInitSearch().currentQuery;
		pikabuBoards = getChan().configuration instanceof PikabuChanConfiguration;
		expandedCategories = Preferences.getExpandedBoardCategories(getPage().chanName);
		if (pikabuBoards && !expandedCategories.contains(EXPANDED_CATEGORIES_INITIALIZED)) {
			expandedCategories.add(EXPANDED_CATEGORIES_INITIALIZED);
			expandedCategories.add(getString(R.string.pikabu_category_feeds));
			expandedCategories.add(getString(R.string.pikabu_category_my_communities));
			Preferences.setExpandedBoardCategories(getPage().chanName, expandedCategories);
		}
		BoardsAdapter adapter = new BoardsAdapter(this, getChan().configuration, pikabuBoards,
				expandedCategories);
		recyclerView.setAdapter(adapter);
		recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(),
				adapter::configureDivider));
		recyclerView.addItemDecoration(new HeaderItemDecoration((c, position) -> adapter.getItemHeader(position)));
		recyclerView.setItemAnimator(null);

		InitRequest initRequest = getInitRequest();
		recyclerView.getPullable().setPullSides(PullableWrapper.Side.TOP);
		ReadViewModel readViewModel = getViewModel(ReadViewModel.class);
		if (initRequest.errorItem != null) {
			switchError(initRequest.errorItem);
		} else {
			recyclerView.getPullable().startBusyState(PullableWrapper.Side.BOTH);
			switchProgress();
			updateBoards();
		}
		readViewModel.observe(this, this);
	}

	@Override
	protected void onDestroy() {
		getAdapter().setCursor(null);
		if (getTask != null) {
			getTask.cancel();
			getTask = null;
		}
	}

	@Override
	public String obtainTitle() {
		boolean hasUserBoards = getChan().configuration.getOption(ChanConfiguration.OPTION_READ_USER_BOARDS);
		return getString(hasUserBoards ? R.string.general_boards : R.string.boards);
	}

	@Override
	public void onItemClick(ChanDatabase.BoardItem boardItem) {
		getUiManager().navigator().navigateBoardsOrThreads(getPage().chanName, boardItem.boardName);

	}

	@Override
	public boolean onItemLongClick(ChanDatabase.BoardItem boardItem) {
		showItemPopupMenu(getFragmentManager(), getPage().chanName, boardItem);
		return true;
	}

	private void showItemPopupMenu(FragmentManager fragmentManager,
			String chanName, ChanDatabase.BoardItem boardItem) {
		new InstanceDialog(fragmentManager, null, provider -> {
			DialogMenu dialogMenu = new DialogMenu(provider.getContext());
			dialogMenu.add(R.string.copy_link, () -> {
				Chan chan = Chan.get(chanName);
				Uri uri = chan.locator.safe(true).createBoardUri(boardItem.boardName, 0);
				if (uri != null) {
					StringUtils.copyToClipboard(provider.getContext(), uri.toString());
				}
			});
			if (!FavoritesStorage.getInstance().hasFavorite(chanName, boardItem.boardName, null)) {
				dialogMenu.add(R.string.add_to_favorites, () -> FavoritesStorage.getInstance()
						.add(chanName, boardItem.boardName));
			}
			if (pikabuBoards && Preferences.getPikabuCustomCommunities().contains(boardItem.boardName)) {
				dialogMenu.add(R.string.pikabu_remove_from_my_communities, () -> {
					if (Preferences.removePikabuCustomCommunity(boardItem.boardName)) refreshBoards(true);
				});
			}
			return dialogMenu.create();
		});
	}

	@Override
	public void onCategoryExpandedChanged(String category, boolean expanded) {
		Preferences.setExpandedBoardCategories(getPage().chanName, expandedCategories);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu) {
		menu.add(0, R.id.menu_search, 0, R.string.filter)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
		menu.add(0, R.id.menu_refresh, 0, R.string.refresh)
				.setIcon(getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance);
		menu.add(0, R.id.menu_make_home_page, 0, R.string.make_home_page)
				.setVisible(Preferences.getDefaultBoardName(getChan()) != null);
		menu.add(0, R.id.menu_add_pikabu_community, 0, R.string.pikabu_add_community)
				.setVisible(pikabuBoards);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.menu_refresh) {
			RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
			if (!retainableExtra.firstLoad) {
				refreshBoards(!getAdapter().isRealEmpty());
			}
			return true;
		} else if (item.getItemId() == R.id.menu_make_home_page) {
			Preferences.setDefaultBoardName(getPage().chanName, null);
			item.setVisible(false);
			return true;
		} else if (item.getItemId() == R.id.menu_add_pikabu_community) {
			showAddPikabuCommunityDialog();
			return true;
		}
		return false;
	}

	private void showAddPikabuCommunityDialog() {
		new InstanceDialog(getFragmentManager(), null, provider -> {
			EditText editText = new SafePasteEditText(provider.getContext());
			editText.setSingleLine(true);
			editText.setHint(R.string.pikabu_add_community__hint);
			editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
			LinearLayout container = new LinearLayout(provider.getContext());
			container.addView(editText, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
			int padding = provider.getContext().getResources().getDimensionPixelSize(R.dimen.dialog_padding_view);
			container.setPadding(padding, padding, padding, padding);
			AlertDialog dialog = new AlertDialog.Builder(provider.getContext())
					.setTitle(R.string.pikabu_add_community)
					.setView(container)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok, null).create();
			dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
					.setOnClickListener(view -> {
						String boardName = PikabuChanLocator.normalizeCommunityInput(
								editText.getText().toString());
						if (boardName == null) {
							ClickableToast.show(R.string.pikabu_invalid_community);
						} else if (!Preferences.addPikabuCustomCommunity(boardName)) {
							ClickableToast.show(R.string.pikabu_community_already_added);
						} else {
							expandedCategories.add(getString(R.string.pikabu_category_my_communities));
							Preferences.setExpandedBoardCategories(getPage().chanName, expandedCategories);
							dialog.dismiss();
							refreshBoards(true);
						}
					}));
			return dialog;
		});
	}

	@Override
	public void onSearchQueryChange(String query) {
		searchQuery = query;
		updateBoards();
	}

	@Override
	public void onListPulled(PullableWrapper wrapper, PullableWrapper.Side side) {
		refreshBoards(true);
	}

	private void updateBoards() {
		if (getTask != null) {
			getTask.cancel();
		}
		getTask = new GetBoardsTask(this, getChan(), null, searchQuery);
		getTask.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
	}

	private void refreshBoards(boolean showPull) {
		if (pikabuBoards) {
			getRetainableExtra(RetainableExtra.FACTORY).pikabuCatalogRefreshAttempted = true;
		}
		ReadViewModel readViewModel = getViewModel(ReadViewModel.class);
		ReadBoardsTask task = new ReadBoardsTask(readViewModel.callback, getChan());
		task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
		readViewModel.attach(task);
		PaddedRecyclerView recyclerView = getRecyclerView();
		if (showPull) {
			recyclerView.getPullable().startBusyState(PullableWrapper.Side.TOP);
			switchList();
		} else {
			recyclerView.getPullable().startBusyState(PullableWrapper.Side.BOTH);
			switchProgress();
		}
	}

	@Override
	public void onGetBoardsResult(ChanDatabase.BoardCursor cursor) {
		getTask = null;
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		boolean firstLoad = retainableExtra.firstLoad;
		retainableExtra.firstLoad = false;
		ReadViewModel readViewModel = getViewModel(ReadViewModel.class);
		ListPosition listPosition = takeListPosition();
		if (cursor == null || !cursor.hasItems) {
			if (cursor != null) {
				cursor.close();
			}
			getAdapter().setCursor(null);
			if (!readViewModel.hasTaskOrValue()) {
				if (firstLoad) {
					refreshBoards(false);
				} else {
					onReadBoardsFail(new ErrorItem(ErrorItem.Type.EMPTY_RESPONSE));
				}
			}
		} else {
			switchList();
			getAdapter().setCursor(cursor);
			PaddedRecyclerView recyclerView = getRecyclerView();
			if (listPosition != null) {
				listPosition.apply(recyclerView);
			}
			if (readViewModel.hasTaskOrValue()) {
				recyclerView.getPullable().startBusyState(PullableWrapper.Side.TOP);
			} else if (pikabuBoards && !retainableExtra.pikabuCatalogRefreshAttempted
					&& !Preferences.isPikabuCommunityCatalogCurrent()) {
				refreshBoards(true);
			}
		}
	}

	@Override
	public void onReadBoardsSuccess() {
		PaddedRecyclerView recyclerView = getRecyclerView();
		recyclerView.getPullable().cancelBusyState();
		updateBoards();
		recyclerView.scrollToPosition(0);
	}

	@Override
	public void onReadBoardsFail(ErrorItem errorItem) {
		getRecyclerView().getPullable().cancelBusyState();
		if (getAdapter().isRealEmpty()) {
			switchError(errorItem);
		} else {
			ClickableToast.show(errorItem);
		}
	}
}
