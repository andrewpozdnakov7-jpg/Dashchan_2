package com.mishiranu.dashchan.ui.navigator.page;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import chan.content.RedirectException;
import chan.http.HttpValidator;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.HidePerformer;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.ReadThreadsTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.content.database.CommonDatabase;
import com.mishiranu.dashchan.content.model.AttachmentItem;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.service.PostingService;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.content.translation.TranslationController;
import com.mishiranu.dashchan.ui.DialogMenu;
import com.mishiranu.dashchan.ui.DrawerForm;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.navigator.Page;
import com.mishiranu.dashchan.ui.navigator.adapter.ThreadsAdapter;
import com.mishiranu.dashchan.ui.navigator.manager.DialogUnit;
import com.mishiranu.dashchan.ui.navigator.manager.UiManager;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.ListPosition;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import com.mishiranu.dashchan.widget.PullableWrapper;
import com.mishiranu.dashchan.widget.SummaryLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ThreadsPage extends ListPage implements ThreadsAdapter.Callback,
		FavoritesStorage.Observer, UiManager.Observer, ReadThreadsTask.Callback {
	private static final int MIN_VISIBLE_THREADS = 10;
	private static final int MAX_UNKNOWN_PAGES_AUTO_FILL = 10;

	private static class RetainableExtra implements Retainable {
		public static final ExtraFactory<RetainableExtra> FACTORY = RetainableExtra::new;

		public final ArrayList<List<PostItem>> cachedPostItems = new ArrayList<>();
		public final ArrayList<List<PostItem>> publishedPostItems = new ArrayList<>();
		public final PostItem.HideState.Map<String> hiddenThreads = new PostItem.HideState.Map<>();
		public int startPageNumber;
		public int boardSpeed;
		public HttpValidator validator;
		public Boolean translationEnabled;
		public int visibleThreadsTarget;
		public int autoFillPages;
		public int autoFillPageNumber = Integer.MIN_VALUE;
		public boolean autoFillRequested;
		public boolean noMoreThreadsShown;
		public boolean pendingThreadsUpdate;
		public boolean pendingThreadsReset;

		public DialogUnit.StackInstance.State dialogsState;

		@Override
		public void clear() {
			if (dialogsState != null) {
				dialogsState.dropState();
				dialogsState = null;
			}
		}
	}

	public static class ReadViewModel extends TaskViewModel.Proxy<ReadThreadsTask, ReadThreadsTask.Callback> {}

	private HidePerformer hidePerformer;

	private final UiManager.PostStateProvider postStateProvider = new UiManager.PostStateProvider() {
		@Override
		public boolean isHiddenResolve(PostItem postItem) {
			if (postItem.getHideState() == PostItem.HideState.UNDEFINED) {
				RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
				PostItem.HideState hideState = retainableExtra.hiddenThreads.get(postItem.getThreadNumber());
				if (hideState != PostItem.HideState.UNDEFINED) {
					postItem.setHidden(hideState, null);
				} else {
					String hideReason = hidePerformer.checkHidden(getChan(), postItem);
					if (hideReason != null) {
						postItem.setHidden(PostItem.HideState.HIDDEN, hideReason);
					} else {
						postItem.setHidden(PostItem.HideState.SHOWN, null);
					}
				}
			}
			return postItem.getHideState().hidden;
		}
	};

	private ThreadsAdapter getAdapter() {
		return (ThreadsAdapter) getRecyclerView().getAdapter();
	}

	@Override
	protected void onCreate() {
		Context context = getContext();
		PaddedRecyclerView recyclerView = getRecyclerView();
		GridLayoutManager layoutManager = new GridLayoutManager(recyclerView.getContext(), 1);
		recyclerView.setLayoutManager(layoutManager);
		Page page = getPage();
		Chan chan = getChan();
		hidePerformer = new HidePerformer(context);
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		UiManager uiManager = getUiManager();
		uiManager.view().bindThreadsPostRecyclerView(recyclerView);
		ThreadsAdapter adapter = new ThreadsAdapter(context, this, page.chanName, uiManager,
				postStateProvider, getFragmentManager());
		adapter.setSecretAbuBoardName(page.boardName);
		if (retainableExtra.translationEnabled == null) {
			retainableExtra.translationEnabled = TranslationController.isReadyForChan(page.chanName) &&
					Preferences.isTranslationAutoEnabled();
		}
		adapter.setTranslationEnabled(Boolean.TRUE.equals(retainableExtra.translationEnabled));
		recyclerView.setAdapter(adapter);
		layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
			@Override
			public int getSpanSize(int position) {
				return adapter.isSecretAbuPosition(position) ? layoutManager.getSpanCount() : 1;
			}
		});
		if (Preferences.isHideThreadsWithSwipe()) {
			setupHideThreadsWithSwipe(recyclerView);
		}
		recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
			@Override
			public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
					@NonNull RecyclerView.State state) {
				int column = ((GridLayoutManager.LayoutParams) view.getLayoutParams()).getSpanIndex();
				adapter.applyItemPadding(view, parent.getChildAdapterPosition(view), column, outRect);
			}
		});
		recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), adapter::configureDivider));
		recyclerView.getPullable().setPullSides(PullableWrapper.Side.BOTH);
		uiManager.observable().register(this);
		layoutManager.setSpanCount(adapter.setThreadsView(Preferences.getThreadsView()));
		ChanConfiguration.Board initialBoard = getChan().configuration.safe().obtainBoard(page.boardName);
		adapter.setThreadsSortingEnabled(initialBoard.allowThreadsSorting, initialBoard.allowRatingSorting);
		adapter.setCatalogSort(Preferences.getCatalogSort());
		adapter.applyFilter(getInitSearch().currentQuery);
		FavoritesStorage.getInstance().getObservable().register(this);

		InitRequest initRequest = getInitRequest();
		ReadViewModel readViewModel = getViewModel(ReadViewModel.class);
		ListPosition listPosition = takeListPosition();
		boolean restoredCachedItems = false;
		if (initRequest.errorItem != null) {
			switchError(initRequest.errorItem);
		} else {
			boolean load = true;
			if (!initRequest.shouldLoad && !retainableExtra.cachedPostItems.isEmpty()) {
				load = false;
				restoredCachedItems = true;
				adapter.setItems(retainableExtra.pendingThreadsUpdate
						? retainableExtra.publishedPostItems : retainableExtra.cachedPostItems,
						retainableExtra.startPageNumber == PAGE_NUMBER_CATALOG);
				ensureVisibleThreadsTarget(retainableExtra);
				if (listPosition != null) {
					listPosition.apply(recyclerView);
				}
				if (retainableExtra.dialogsState != null) {
					uiManager.dialog().restoreState(adapter.getConfigurationSet(), retainableExtra.dialogsState);
					retainableExtra.dialogsState.dropState();
					retainableExtra.dialogsState = null;
				}
			}
			if (readViewModel.hasTaskOrValue()) {
				if (getAdapter().isRealEmpty()) {
					recyclerView.getPullable().startBusyState(PullableWrapper.Side.BOTH);
					switchProgress();
				} else if (retainableExtra.autoFillPageNumber == Integer.MIN_VALUE) {
					ReadThreadsTask task = readViewModel.getTask();
					boolean bottom = task != null && task.getPageNumber() > retainableExtra.startPageNumber;
					recyclerView.getPullable().startBusyState(bottom
							? PullableWrapper.Side.BOTTOM : PullableWrapper.Side.TOP);
				}
			} else if (load) {
				ChanConfiguration.Board board = chan.configuration.safe().obtainBoard(page.boardName);
				retainableExtra.cachedPostItems.clear();
				retainableExtra.startPageNumber = board.allowCatalog && Preferences.isLoadCatalog(chan)
						? PAGE_NUMBER_CATALOG : 0;
				refreshThreads(RefreshPage.CURRENT, false);
			}
		}
		readViewModel.observe(this, this);
		if (restoredCachedItems && !readViewModel.hasTaskOrValue() && hasRemovedHiddenThreads(retainableExtra)) {
			requestHiddenThreadsAutoFill(false);
		}
		if (restoredCachedItems && initRequest.errorItem == null) updateSecretAbuThread();
	}

	private void setupHideThreadsWithSwipe(RecyclerView recyclerView) {
		ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback
				(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
			@Override
			public int getSwipeDirs(@NonNull RecyclerView recyclerView,
					@NonNull RecyclerView.ViewHolder viewHolder) {
				int position = viewHolder.getAdapterPosition();
				ThreadsAdapter adapter = getAdapter();
				if (position == RecyclerView.NO_POSITION || position >= adapter.getItemCount()
						|| adapter.isSecretAbuPosition(position)) {
					return 0;
				}
				PostItem postItem = adapter.getThread(position);
				return postItem.getHideState().hidden ? 0 : super.getSwipeDirs(recyclerView, viewHolder);
			}

			@Override
			public boolean onMove(@NonNull RecyclerView recyclerView,
					@NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
				return false;
			}

			@Override
			public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
				int position = viewHolder.getAdapterPosition();
				ThreadsAdapter adapter = getAdapter();
				if (position != RecyclerView.NO_POSITION && position < adapter.getItemCount()
						&& !adapter.isSecretAbuPosition(position)) {
					PostItem postItem = adapter.getThread(position);
					hideThread(postItem, true);
				} else {
					adapter.notifyDataSetChanged();
				}
			}

			@Override
			public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView recyclerView,
					@NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY,
					int actionState, boolean isCurrentlyActive) {
				if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
					float width = Math.max(1f, viewHolder.itemView.getWidth());
					float alpha = Math.max(0f, 1f - 1.5f * Math.abs(dX) / width);
					viewHolder.itemView.setAlpha(alpha);
				}
				super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
			}

			@Override
			public void clearView(@NonNull RecyclerView recyclerView,
					@NonNull RecyclerView.ViewHolder viewHolder) {
				viewHolder.itemView.setAlpha(1f);
				super.clearView(recyclerView, viewHolder);
			}
		};
		new ItemTouchHelper(callback).attachToRecyclerView(recyclerView);
	}

	@Override
	protected void onResume() {
		super.onResume();
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		if (retainableExtra.dialogsState != null) {
			retainableExtra.dialogsState.dropState();
			retainableExtra.dialogsState = null;
		}
	}

	@Override
	protected void onDestroy() {
		getUiManager().dialog().closeDialogs(getAdapter().getConfigurationSet().stackInstance);
		getUiManager().observable().unregister(this);
		FavoritesStorage.getInstance().getObservable().unregister(this);
	}

	@Override
	protected void onNotifyAllAdaptersChanged() {
		getUiManager().dialog().notifyDataSetChangedToAll(getAdapter().getConfigurationSet().stackInstance);
	}

	@Override
	protected void onHandleNewPostDataList() {
		Page page = getPage();
		PostingService.NewPostData newPostData = PostingService.consumeNewThreadData(getContext(),
				page.chanName, page.boardName);
		if (newPostData != null) {
			getUiManager().navigator().navigatePosts(newPostData.key.chanName, newPostData.key.boardName,
					newPostData.key.threadNumber, null, null);
		}
	}

	@Override
	protected void onRequestStoreExtra(boolean saveToStack) {
		ThreadsAdapter adapter = getAdapter();
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		if (retainableExtra.dialogsState != null) {
			retainableExtra.dialogsState.dropState();
		}
		retainableExtra.dialogsState = adapter.getConfigurationSet().stackInstance.collectState();
	}

	@Override
	public Pair<String, String> obtainTitleSubtitle() {
		Page page = getPage();
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		String title = getChan().configuration.formatBoardTitle(page.boardName);
		String subtitle = null;
		if (retainableExtra.startPageNumber > 0) {
			subtitle = getString(R.string.number_page__format, retainableExtra.startPageNumber);
		} else if (retainableExtra.startPageNumber == PAGE_NUMBER_CATALOG) {
			subtitle = getString(R.string.catalog);
		} else if (retainableExtra.boardSpeed > 0) {
			subtitle = getResources().getQuantityString(R.plurals.number_posts_per_hour__format,
					retainableExtra.boardSpeed, retainableExtra.boardSpeed);
		}
		return new Pair<>(title, subtitle);
	}

	@Override
	public void onItemClick(PostItem postItem) {
		if (postItem != null) {
			Page page = getPage();
			if (postItem.getHideState().hidden) {
				setThreadHideState(postItem, PostItem.HideState.SHOWN);
				updateSecretAbuThread();
				getAdapter().notifyDataSetChanged();
			} else {
				getUiManager().navigator().navigatePosts(page.chanName, page.boardName,
						postItem.getThreadNumber(), null, postItem.getSubjectOrComment());
			}
		}
	}

	@Override
	public boolean onItemLongClick(PostItem postItem) {
		if (postItem != null) {
			showItemPopupMenu(getFragmentManager(), postItem);
			return true;
		}
		return false;
	}

	private static void showItemPopupMenu(FragmentManager fragmentManager, PostItem postItem) {
		new InstanceDialog(fragmentManager, null, provider -> {
			ThreadsPage threadsPage = extract(provider);
			Page page = threadsPage.getPage();
			DialogMenu dialogMenu = new DialogMenu(provider.getContext());
			dialogMenu.add(R.string.copy_link, () -> {
				Uri uri = threadsPage.getChan().locator.safe(true)
						.createThreadUri(page.boardName, postItem.getThreadNumber());
				if (uri != null) {
					StringUtils.copyToClipboard(provider.getContext(), uri.toString());
				}
			});
			dialogMenu.add(R.string.share_link, () -> {
				Uri uri = threadsPage.getChan().locator.safe(true)
						.createThreadUri(page.boardName, postItem.getThreadNumber());
				String subject = postItem.getSubjectOrComment();
				if (StringUtils.isEmptyOrWhitespace(subject)) {
					subject = uri.toString();
				}
				NavigationUtils.shareLink(provider.getContext(), subject, uri);
			});
			if (!postItem.getHideState().hidden) {
				dialogMenu.add(R.string.hide, () -> {
					threadsPage.hideThread(postItem, false);
				});
			}
			return dialogMenu.create();
		});
	}

	private void setThreadHideState(PostItem postItem, PostItem.HideState hideState) {
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		retainableExtra.hiddenThreads.set(postItem.getThreadNumber(), hideState);
		CommonDatabase.getInstance().getThreads().setFlagsAsync(getPage().chanName,
				postItem.getBoardName(), postItem.getThreadNumber(), hideState);
		postItem.setHidden(hideState, null);
	}

	private void hideThread(PostItem postItem, boolean showConfirmation) {
		setThreadHideState(postItem, PostItem.HideState.HIDDEN);
		getAdapter().notifyThreadHidden(postItem);
		if (showConfirmation) {
			ClickableToast.show(R.string.thread_hidden);
		}
		requestHiddenThreadsAutoFill(true);
	}

	private boolean allowSearch = false;

	@Override
	public void onCreateOptionsMenu(Menu menu) {
		menu.add(0, R.id.menu_refresh, 0, R.string.refresh)
				.setIcon(getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.add(0, R.id.menu_translate, 0, R.string.translate_posts)
				.setIcon(getActionBarIcon(R.attr.iconActionTranslate))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, R.id.menu_search, 0, R.string.search)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
		menu.add(0, R.id.menu_catalog, 0, R.string.catalog);
		menu.add(0, R.id.menu_pages, 0, R.string.pages);
		SubMenu sorting = menu.addSubMenu(0, R.id.menu_sorting, 0, R.string.sorting);
		for (Preferences.CatalogSort catalogSort : Preferences.CatalogSort.values()) {
			sorting.add(R.id.menu_sorting, catalogSort.menuItemId, 0, catalogSort.titleResId);
		}
		sorting.setGroupCheckable(R.id.menu_sorting, true, true);
		menu.add(0, R.id.menu_archive, 0, R.string.archive);
		menu.add(0, R.id.menu_new_thread, 0, R.string.new_thread);
		menu.add(0, R.id.menu_summary, 0, R.string.summary);
		menu.addSubMenu(0, R.id.menu_appearance, 0, R.string.appearance);
		SubMenu viewOptions = menu.addSubMenu(0, R.id.menu_threads_view, 0, R.string.threads_view);
		for (Preferences.ThreadsView threadsView : Preferences.ThreadsView.values()) {
			viewOptions.add(R.id.menu_threads_view, threadsView.menuItemId, 0, threadsView.titleResId);
		}
		viewOptions.setGroupCheckable(R.id.menu_threads_view, true, true);
		menu.add(0, R.id.menu_star_text, 0, R.string.add_to_favorites);
		menu.add(0, R.id.menu_unstar_text, 0, R.string.remove_from_favorites);
		menu.add(0, R.id.menu_star_icon, 0, R.string.add_to_favorites)
				.setIcon(getActionBarIcon(R.attr.iconActionAddToFavorites))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, R.id.menu_unstar_icon, 0, R.string.remove_from_favorites)
				.setIcon(getActionBarIcon(R.attr.iconActionRemoveFromFavorites))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, R.id.menu_make_home_page, 0, R.string.make_home_page);
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		Page page = getPage();
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		Chan chan = getChan();
		ChanConfiguration.Board board = chan.configuration.safe().obtainBoard(page.boardName);
		this.allowSearch = board.allowSearch;
		boolean isCatalogOpen = retainableExtra.startPageNumber == PAGE_NUMBER_CATALOG;
		MenuItem translateItem = menu.findItem(R.id.menu_translate);
		boolean showTranslation = TranslationController.isEnabledForChan(page.chanName);
		if ((!showTranslation || !TranslationController.isReadyForChan(page.chanName)) &&
				getAdapter().isTranslationEnabled()) {
			retainableExtra.translationEnabled = false;
			getAdapter().setTranslationEnabled(false);
		}
		translateItem.setVisible(showTranslation);
		if (showTranslation) {
			translateItem.setTitle(getAdapter().isTranslationEnabled()
					? R.string.show_original_posts : R.string.translate_posts);
		}
		menu.findItem(R.id.menu_search).setTitle(board.allowSearch ? R.string.search : R.string.filter);
		menu.findItem(R.id.menu_catalog).setVisible(board.allowCatalog && !isCatalogOpen);
		menu.findItem(R.id.menu_pages).setVisible(board.allowCatalog && isCatalogOpen);
		menu.findItem(R.id.menu_sorting).setVisible(board.allowThreadsSorting || board.allowCatalog && isCatalogOpen);
		menu.findItem(R.id.menu_rating).setVisible(board.allowRatingSorting);
		menu.findItem(R.id.menu_unsorted).setTitle(board.allowThreadsSorting && !isCatalogOpen
				? R.string.last_bump : R.string.unsorted);
		Preferences.CatalogSort catalogSort = Preferences.getCatalogSort();
		if (catalogSort == Preferences.CatalogSort.RATING && !board.allowRatingSorting) {
			catalogSort = Preferences.CatalogSort.UNSORTED;
		}
		menu.findItem(catalogSort.menuItemId).setChecked(true);
		menu.findItem(R.id.menu_archive).setVisible(board.allowArchive);
		menu.findItem(R.id.menu_new_thread).setVisible(board.allowPosting
				&& chan.configuration.safe().obtainPosting(page.boardName, true) != null);
		menu.findItem(Preferences.getThreadsView().menuItemId).setChecked(true);
		boolean singleBoardMode = chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE);
		boolean isFavorite = FavoritesStorage.getInstance().hasFavorite(page.chanName, page.boardName, null);
		boolean iconFavorite = ResourceUtils.isTabletOrLandscape(getResources().getConfiguration());
		menu.findItem(R.id.menu_star_text).setVisible(!iconFavorite && !isFavorite && !singleBoardMode);
		menu.findItem(R.id.menu_unstar_text).setVisible(!iconFavorite && isFavorite);
		menu.findItem(R.id.menu_star_icon).setVisible(iconFavorite && !isFavorite && !singleBoardMode);
		menu.findItem(R.id.menu_unstar_icon).setVisible(iconFavorite && isFavorite);
		menu.findItem(R.id.menu_make_home_page).setVisible(!singleBoardMode &&
				!CommonUtils.equals(page.boardName, Preferences.getDefaultBoardName(chan)));
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Page page = getPage();
		switch (item.getItemId()) {
			case R.id.menu_refresh: {
				refreshThreads(RefreshPage.CURRENT);
				return true;
			}
			case R.id.menu_translate: {
				if (!TranslationController.isReadyForChan(page.chanName)) {
					ClickableToast.show(R.string.translation_package_unavailable);
					return true;
				}
				boolean enabled = !getAdapter().isTranslationEnabled();
				boolean initializing = false;
				if (enabled) {
					GridLayoutManager layoutManager = (GridLayoutManager) getRecyclerView().getLayoutManager();
					initializing = getAdapter().hasUntranslatedThreads(layoutManager.findFirstVisibleItemPosition(),
							layoutManager.findLastVisibleItemPosition());
				}
				getRetainableExtra(RetainableExtra.FACTORY).translationEnabled = enabled;
				getAdapter().setTranslationEnabled(enabled);
				if (initializing) {
					ClickableToast.show(R.string.translation_initializing);
				}
				updateOptionsMenu();
				return true;
			}
			case R.id.menu_catalog: {
				loadThreadsPage(PAGE_NUMBER_CATALOG, false);
				return true;
			}
			case R.id.menu_pages: {
				loadThreadsPage(0, false);
				return true;
			}
			case R.id.menu_archive: {
				getUiManager().navigator().navigateArchive(page.chanName, page.boardName);
				return true;
			}
			case R.id.menu_new_thread: {
				getUiManager().navigator().navigatePosting(page.chanName, page.boardName, null);
				return true;
			}
			case R.id.menu_summary: {
				showSummaryDialog(getFragmentManager(), page.chanName, page.boardName);
				return true;
			}
			case R.id.menu_star_text:
			case R.id.menu_star_icon: {
				FavoritesStorage.getInstance().add(page.chanName, page.boardName);
				return true;
			}
			case R.id.menu_unstar_text:
			case R.id.menu_unstar_icon: {
				FavoritesStorage.getInstance().remove(page.chanName, page.boardName, null);
				return true;
			}
			case R.id.menu_make_home_page: {
				Preferences.setDefaultBoardName(page.chanName, page.boardName);
				item.setVisible(false);
				return true;
			}
		}
		for (Preferences.CatalogSort catalogSort : Preferences.CatalogSort.values()) {
			if (item.getItemId() == catalogSort.menuItemId) {
				Preferences.setCatalogSort(catalogSort);
				getAdapter().setCatalogSort(catalogSort);
				return true;
			}
		}
		for (Preferences.ThreadsView threadsView : Preferences.ThreadsView.values()) {
			if (item.getItemId() == threadsView.menuItemId) {
				Preferences.setThreadsView(threadsView);
				GridLayoutManager gridLayoutManager = (GridLayoutManager) getRecyclerView().getLayoutManager();
				gridLayoutManager.setSpanCount(getAdapter().setThreadsView(threadsView));
				getAdapter().notifyDataSetChanged();
				return true;
			}
		}
		return false;
	}

	private static void showSummaryDialog(FragmentManager fragmentManager, String chanName, String boardName) {
		new InstanceDialog(fragmentManager, null, provider -> {
			Chan chan = Chan.get(chanName);
			Context context = provider.getContext();
			AlertDialog dialog = new AlertDialog.Builder(context)
					.setTitle(R.string.summary)
					.setPositiveButton(android.R.string.ok, null)
					.create();
			SummaryLayout layout = new SummaryLayout(dialog);
			if (boardName != null) {
				String title = chan.configuration.formatBoardTitle(boardName);
				layout.add(context.getString(R.string.board), title);
				String description = chan.configuration.getBoardDescription(boardName);
				if (!StringUtils.isEmpty(description)) {
					layout.add(context.getString(R.string.description), description);
				}
			}
			int pagesCount = Math.max(chan.configuration.getPagesCount(boardName), 1);
			if (pagesCount != ChanConfiguration.PAGES_COUNT_INVALID) {
				layout.add(context.getString(R.string.pages_count), Integer.toString(pagesCount));
			}
			ChanConfiguration.Board board = chan.configuration.safe().obtainBoard(boardName);
			ChanConfiguration.Posting posting = board.allowPosting
					? chan.configuration.safe().obtainPosting(boardName, true) : null;
			if (posting != null) {
				int bumpLimit = chan.configuration.getBumpLimit(boardName);
				if (bumpLimit != ChanConfiguration.BUMP_LIMIT_INVALID) {
					layout.add(context.getString(R.string.bump_limit), context.getResources()
							.getQuantityString(R.plurals.number_posts__format, bumpLimit, bumpLimit));
				}
			}
			layout.addDivider();
			if (posting != null) {
				StringBuilder builder = new StringBuilder();
				if (!posting.allowSubject) {
					builder.append("\u2022 ").append(context.getString(R.string.subjects_are_disabled)).append('\n');
				}
				if (!posting.allowName) {
					builder.append("\u2022 ").append(context.getString(R.string.names_are_disabled)).append('\n');
				} else if (!posting.allowTripcode) {
					builder.append("\u2022 ").append(context.getString(R.string.tripcodes_are_disabled)).append('\n');
				}
				if (posting.attachmentCount <= 0) {
					builder.append("\u2022 ").append(context.getString(R.string.images_are_disabled)).append('\n');
				}
				if (!posting.optionSage) {
					builder.append("\u2022 ").append(context.getString(R.string.sage_is_disabled)).append('\n');
				}
				if (posting.hasCountryFlags) {
					builder.append("\u2022 ").append(context.getString(R.string.flags_are_enabled)).append('\n');
				}
				if (posting.userIcons.size() > 0) {
					builder.append("\u2022 ").append(context.getString(R.string.icons_are_enabled)).append('\n');
				}
				if (builder.length() > 0) {
					builder.setLength(builder.length() - 1);
					layout.add(context.getString(R.string.configuration), builder);
				}
			} else {
				layout.add(context.getString(R.string.configuration), context.getString(R.string.read_only));
			}
			return dialog;
		});
	}

	@Override
	public void onFavoritesUpdate(FavoritesStorage.FavoriteItem favoriteItem, FavoritesStorage.Action action) {
		switch (action) {
			case ADD:
			case REMOVE: {
				Page page = getPage();
				if (favoriteItem.equals(page.chanName, page.boardName, null)) {
					updateOptionsMenu();
				}
				break;
			}
		}
	}

	@Override
	public void onAppearanceOptionChanged(int what) {
		switch (what) {
			case R.id.menu_spoilers:
			case R.id.menu_sfw_mode: {
				notifyAllAdaptersChanged();
				break;
			}
		}
	}

	@Override
	public boolean onSearchSubmit(String query) {
		if (allowSearch) {
			// Collapse search view
			getRecyclerView().post(() -> {
				Page page = getPage();
				getUiManager().navigator().navigateSearch(page.chanName, page.boardName, query);
			});
			return true;
		}
		return false;
	}

	@Override
	public int onDrawerNumberEntered(int number) {
		int result = 0;
		if (number >= 0) {
			// loadDesiredThreadsPage will leave error message, if number is incorrect
			result |= DrawerForm.RESULT_REMOVE_ERROR_MESSAGE;
			if (loadThreadsPage(number, false)) {
				result |= DrawerForm.RESULT_SUCCESS;
			}
		}
		return result;
	}

	@Override
	public void onSearchQueryChange(String query) {
		getAdapter().applyFilter(query);
	}

	@Override
	public void onListPulled(PullableWrapper wrapper, PullableWrapper.Side side) {
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		refreshThreads(getAdapter().isRealEmpty() || retainableExtra.startPageNumber == PAGE_NUMBER_CATALOG
				? RefreshPage.CURRENT : side == PullableWrapper.Side.BOTTOM
				? RefreshPage.NEXT : RefreshPage.PREVIOUS, true);
	}

	private enum RefreshPage {CURRENT, PREVIOUS, NEXT, CATALOG}

	private static final int PAGE_NUMBER_CATALOG = ChanPerformer.ReadThreadsData.PAGE_NUMBER_CATALOG;

	private void refreshThreads(RefreshPage refreshPage) {
		refreshThreads(refreshPage, !getAdapter().isRealEmpty());
	}

	private void refreshThreads(RefreshPage refreshPage, boolean showPull) {
		cancelHiddenThreadsAutoFill();
		int pageNumber;
		boolean append = false;
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		if (refreshPage == RefreshPage.CATALOG || refreshPage == RefreshPage.CURRENT &&
				retainableExtra.startPageNumber == PAGE_NUMBER_CATALOG) {
			pageNumber = PAGE_NUMBER_CATALOG;
		} else {
			int currentPageNumber = retainableExtra.startPageNumber;
			if (!retainableExtra.cachedPostItems.isEmpty()) {
				currentPageNumber += retainableExtra.cachedPostItems.size() - 1;
			}
			boolean pageByPage = Preferences.isPageByPage();
			if (pageByPage) {
				pageNumber = refreshPage == RefreshPage.NEXT ? currentPageNumber + 1
						: refreshPage == RefreshPage.PREVIOUS ? currentPageNumber - 1 : currentPageNumber;
				if (pageNumber < 0) {
					pageNumber = 0;
				}
			} else {
				pageNumber = refreshPage == RefreshPage.NEXT && currentPageNumber >= 0 ? currentPageNumber + 1 : 0;
				if (pageNumber != 0) {
					append = true;
				}
			}
		}
		loadThreadsPage(pageNumber, append, showPull, false);
	}

	private boolean loadThreadsPage(int pageNumber, boolean append) {
		cancelHiddenThreadsAutoFill();
		return loadThreadsPage(pageNumber, append, !getAdapter().isRealEmpty(), false);
	}

	private boolean loadThreadsPage(int pageNumber, boolean append, boolean showPull, boolean autoFill) {
		Page page = getPage();
		Chan chan = getChan();
		ReadViewModel readViewModel = getViewModel(ReadViewModel.class);
		PaddedRecyclerView recyclerView = getRecyclerView();
		if (pageNumber < PAGE_NUMBER_CATALOG || pageNumber >=
				Math.max(chan.configuration.getPagesCount(page.boardName), 1)) {
			recyclerView.getPullable().cancelBusyState();
			if (autoFill) {
				finishHiddenThreadsAutoFill(true);
			} else {
				ClickableToast.show(getString(R.string.number_page_doesnt_exist__format, pageNumber));
			}
			readViewModel.attach(null);
			return false;
		} else {
			RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
			getAdapter().setSecretAbuAllowed(false);
			if (autoFill) {
				retainableExtra.autoFillPageNumber = pageNumber;
			}
			HttpValidator validator = !append && retainableExtra.cachedPostItems.size() == 1
					&& retainableExtra.startPageNumber == pageNumber ? retainableExtra.validator : null;
			ReadThreadsTask task = new ReadThreadsTask(readViewModel.callback,
					chan, page.boardName, pageNumber, validator, append);
			task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
			readViewModel.attach(task);
			if (autoFill) {
				// Keep an existing initial/manual loading indicator, but never start a new one
				// for each page fetched to replace hidden threads.
			} else if (showPull) {
				recyclerView.getPullable().startBusyState(append
						? PullableWrapper.Side.BOTTOM : PullableWrapper.Side.TOP);
				switchList();
			} else {
				recyclerView.getPullable().startBusyState(PullableWrapper.Side.BOTH);
				switchProgress();
			}
			return true;
		}
	}

	private void ensureVisibleThreadsTarget(RetainableExtra retainableExtra) {
		if (retainableExtra.visibleThreadsTarget == 0 && retainableExtra.startPageNumber >= 0 &&
				!retainableExtra.cachedPostItems.isEmpty()) {
			retainableExtra.visibleThreadsTarget = Math.max(MIN_VISIBLE_THREADS,
					retainableExtra.cachedPostItems.get(0).size());
		}
	}

	private boolean hasRemovedHiddenThreads(RetainableExtra retainableExtra) {
		if (Preferences.isDisplayHiddenThreads()) {
			return false;
		}
		int cachedThreadsCount = 0;
		for (List<PostItem> postItems : retainableExtra.cachedPostItems) {
			cachedThreadsCount += postItems.size();
		}
		return countVisibleCachedThreads(retainableExtra) < cachedThreadsCount;
	}

	private int countVisibleCachedThreads(RetainableExtra extra) {
		int count = 0;
		for (List<PostItem> page : extra.cachedPostItems) {
			for (PostItem postItem : page) {
				if (Preferences.isDisplayHiddenThreads() || !postStateProvider.isHiddenResolve(postItem)) count++;
			}
		}
		return count;
	}

	private void requestHiddenThreadsAutoFill(boolean restart) {
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		ensureVisibleThreadsTarget(retainableExtra);
		if (restart) {
			retainableExtra.autoFillPages = 0;
			retainableExtra.noMoreThreadsShown = false;
		}
		retainableExtra.autoFillRequested = true;
		continueHiddenThreadsAutoFill();
	}

	private void continueHiddenThreadsAutoFill() {
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		if (!retainableExtra.autoFillRequested || Preferences.isDisplayHiddenThreads() ||
				Preferences.isPageByPage() || retainableExtra.startPageNumber < 0 ||
				retainableExtra.visibleThreadsTarget <= 0) {
			finishHiddenThreadsAutoFill(false);
			return;
		}
		if (countVisibleCachedThreads(retainableExtra) >= retainableExtra.visibleThreadsTarget) {
			finishHiddenThreadsAutoFill(false);
			return;
		}
		ReadViewModel readViewModel = getViewModel(ReadViewModel.class);
		if (readViewModel.getTask() != null || retainableExtra.autoFillPageNumber != Integer.MIN_VALUE) {
			return;
		}
		Page page = getPage();
		int nextPageNumber = retainableExtra.startPageNumber + retainableExtra.cachedPostItems.size();
		int pagesCount = getChan().configuration.getPagesCount(page.boardName);
		if (pagesCount != ChanConfiguration.PAGES_COUNT_INVALID && nextPageNumber >= pagesCount) {
			finishHiddenThreadsAutoFill(true);
			return;
		}
		if (pagesCount == ChanConfiguration.PAGES_COUNT_INVALID &&
				retainableExtra.autoFillPages >= MAX_UNKNOWN_PAGES_AUTO_FILL) {
			finishHiddenThreadsAutoFill(false);
			return;
		}
		retainableExtra.autoFillPages++;
		loadThreadsPage(nextPageNumber, true, true, true);
	}

	private void cancelHiddenThreadsAutoFill() {
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		retainableExtra.autoFillRequested = false;
		retainableExtra.autoFillPages = 0;
		retainableExtra.autoFillPageNumber = Integer.MIN_VALUE;
		retainableExtra.noMoreThreadsShown = false;
		publishPendingThreads();
	}

	private void finishHiddenThreadsAutoFill(boolean noMoreThreads) {
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		retainableExtra.autoFillRequested = false;
		retainableExtra.autoFillPages = 0;
		retainableExtra.autoFillPageNumber = Integer.MIN_VALUE;
		publishPendingThreads();
		getRecyclerView().getPullable().cancelBusyState();
		switchList();
		if (noMoreThreads && !retainableExtra.noMoreThreadsShown) {
			retainableExtra.noMoreThreadsShown = true;
			ClickableToast.show(R.string.no_more_threads);
		}
		updateSecretAbuThread();
	}

	private void publishPendingThreads() {
		RetainableExtra extra = getRetainableExtra(RetainableExtra.FACTORY);
		if (!extra.pendingThreadsUpdate) return;
		PaddedRecyclerView recyclerView = getRecyclerView();
		ThreadsAdapter adapter = getAdapter();
		ListPosition position = extra.pendingThreadsReset ? null : ListPosition.obtain(recyclerView,
				index -> index < adapter.getItemCount() && !adapter.isSecretAbuPosition(index));
		PostItem anchor = position != null ? adapter.getThread(position.position) : null;
		boolean initiallyEmpty = adapter.isRealEmpty();
		adapter.setItems(extra.cachedPostItems, extra.startPageNumber == PAGE_NUMBER_CATALOG);
		extra.publishedPostItems.clear();
		extra.publishedPostItems.addAll(extra.cachedPostItems);
		if (extra.pendingThreadsReset) {
			recyclerView.scrollToPosition(0);
		} else if (anchor != null) {
			int index = adapter.findThreadPosition(anchor.getThreadNumber());
			if (index >= 0) new ListPosition(index, position.offset).apply(recyclerView);
		}
		extra.pendingThreadsUpdate = false;
		extra.pendingThreadsReset = false;
		notifyTitleChanged();
		updateOptionsMenu();
		if (initiallyEmpty && !adapter.isRealEmpty()) showScaleAnimation();
	}

	private void updateSecretAbuThread() {
		RetainableExtra extra = getRetainableExtra(RetainableExtra.FACTORY);
		ThreadsAdapter adapter = getAdapter();
		boolean show = "dvach".equals(getPage().chanName) && !extra.autoFillRequested
				&& extra.autoFillPageNumber == Integer.MIN_VALUE
				&& getViewModel(ReadViewModel.class).getTask() == null
				&& areAllLoadedThreadsHidden(extra);
		adapter.setSecretAbuAllowed(show);
		if (adapter.isSecretAbuVisible()) switchList();
	}

	private boolean areAllLoadedThreadsHidden(RetainableExtra extra) {
		boolean hasThreads = false;
		for (List<PostItem> page : extra.cachedPostItems) {
			for (PostItem postItem : page) {
				hasThreads = true;
				if (!postStateProvider.isHiddenResolve(postItem)) return false;
			}
		}
		return hasThreads;
	}

	@Override
	public void onReadThreadsSuccess(List<PostItem> postItems, int pageNumber,
			int boardSpeed, boolean append, boolean checkModified, HttpValidator validator,
			PostItem.HideState.Map<String> hiddenThreads) {
		PaddedRecyclerView recyclerView = getRecyclerView();
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		boolean autoFill = pageNumber == retainableExtra.autoFillPageNumber;
		if (autoFill) {
			retainableExtra.autoFillPageNumber = Integer.MIN_VALUE;
		}
		if (postItems != null && postItems.isEmpty()) {
			postItems = null;
		}
		if (retainableExtra.cachedPostItems.isEmpty()) {
			append = false;
		}
		if (hiddenThreads != null) {
			if (!append) {
				retainableExtra.hiddenThreads.clear();
			}
			retainableExtra.hiddenThreads.addAll(hiddenThreads);
		}
		if (postItems != null && append) {
			HashSet<String> threadNumbers = new HashSet<>();
			for (List<PostItem> pagePostItems : retainableExtra.cachedPostItems) {
				for (PostItem postItem : pagePostItems) {
					threadNumbers.add(postItem.getThreadNumber());
				}
			}
			boolean newList = false;
			for (int i = postItems.size() - 1; i >= 0; i--) {
				if (threadNumbers.contains(postItems.get(i).getThreadNumber())) {
					if (!newList) {
						postItems = new ArrayList<>(postItems);
						newList = true;
					}
					postItems.remove(i);
				}
			}
		}
		if (autoFill && (postItems == null || postItems.isEmpty())) {
			finishHiddenThreadsAutoFill(true);
			return;
		}
		ThreadsAdapter adapter = getAdapter();
		if (postItems != null && !postItems.isEmpty()) {
			retainableExtra.validator = validator;
			if (!append) {
				retainableExtra.cachedPostItems.clear();
				retainableExtra.startPageNumber = pageNumber;
				retainableExtra.boardSpeed = boardSpeed;
				retainableExtra.visibleThreadsTarget = pageNumber >= 0
						? Math.max(MIN_VISIBLE_THREADS, postItems.size()) : 0;
				retainableExtra.autoFillPages = 0;
				retainableExtra.noMoreThreadsShown = false;
				retainableExtra.pendingThreadsReset = true;
			} else if (!autoFill) {
				// Each manually requested page should contribute a full portion of visible threads.
				retainableExtra.visibleThreadsTarget = countVisibleCachedThreads(retainableExtra)
						+ Math.max(MIN_VISIBLE_THREADS, postItems.size());
			}
			retainableExtra.cachedPostItems.add(postItems);
			retainableExtra.pendingThreadsUpdate = true;
			if (autoFill) {
				continueHiddenThreadsAutoFill();
			} else if (hasRemovedHiddenThreads(retainableExtra) &&
					countVisibleCachedThreads(retainableExtra) < retainableExtra.visibleThreadsTarget) {
				requestHiddenThreadsAutoFill(false);
			} else {
				publishPendingThreads();
				recyclerView.getPullable().cancelBusyState();
				switchList();
			}
		} else {
			recyclerView.getPullable().cancelBusyState();
			switchList();
			if (checkModified && postItems == null) {
				adapter.notifyNotModified();
				recyclerView.scrollToPosition(0);
			} else if (adapter.isRealEmpty()) {
				switchError(R.string.empty_response);
			} else {
				ClickableToast.show(R.string.empty_response);
			}
		}
		updateSecretAbuThread();
	}

	@Override
	public void onReadThreadsRedirect(RedirectException.Target target) {
		finishHiddenThreadsAutoFill(false);
		getAdapter().setSecretAbuAllowed(false);
		getRecyclerView().getPullable().cancelBusyState();
		if (!CommonUtils.equals(target.chanName, getPage().chanName)) {
			if (getAdapter().isRealEmpty()) {
				switchError(R.string.board_doesnt_exist);
			}
			showRedirectDialog(getFragmentManager(), target);
		} else {
			handleRedirect(target.chanName, target.boardName, null, null);
		}
	}

	@Override
	public void onReadThreadsFail(ErrorItem errorItem, int pageNumber) {
		getRecyclerView().getPullable().cancelBusyState();
		RetainableExtra retainableExtra = getRetainableExtra(RetainableExtra.FACTORY);
		boolean autoFill = pageNumber == retainableExtra.autoFillPageNumber;
		if (autoFill) {
			retainableExtra.autoFillPageNumber = Integer.MIN_VALUE;
			if (errorItem.type == ErrorItem.Type.BOARD_NOT_EXISTS) {
				finishHiddenThreadsAutoFill(true);
				return;
			}
			finishHiddenThreadsAutoFill(false);
		}
		String message = errorItem.type == ErrorItem.Type.BOARD_NOT_EXISTS && pageNumber >= 1
				? getString(R.string.number_page_doesnt_exist__format, pageNumber) : errorItem.toString();
		getAdapter().setSecretAbuAllowed(false);
		if (getAdapter().isRealEmpty()) {
			switchError(message);
		} else {
			ClickableToast.show(message);
		}
	}

	private static void showRedirectDialog(FragmentManager fragmentManager, RedirectException.Target target) {
		String tag = ThreadsPage.class.getName() + ":Redirect";
		new InstanceDialog(fragmentManager, tag, provider -> {
			ThreadsPage threadsPage = extract(provider);
			String message = provider.getContext().getString(R.string.open_forum__format_sentence,
					Chan.get(target.chanName).configuration.getTitle());
			return new AlertDialog.Builder(provider.getContext())
					.setMessage(message)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok, (d, which) -> threadsPage
							.handleRedirect(target.chanName, target.boardName, null, null))
					.create();
		});
	}

	@Override
	public void onReloadAttachmentItem(AttachmentItem attachmentItem) {
		getAdapter().reloadAttachment(attachmentItem);
	}
}
