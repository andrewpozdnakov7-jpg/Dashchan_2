package com.mishiranu.dashchan.ui.navigator;

import com.mishiranu.dashchan.widget.ContentStateMotion;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Pair;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.core.view.OneShotPreDrawListener;
import androidx.lifecycle.ViewModelProvider;
import chan.content.Chan;
import chan.content.ChanLocator;
import chan.content.ChanPerformer;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.chan.soyjak.SoyjakDiagnostics;
import com.mishiranu.dashchan.chan.soyjak.SoyjakReadRecovery;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ThreadMotionKey;
import com.mishiranu.dashchan.content.WallpaperManager;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.ui.ContentFragment;
import com.mishiranu.dashchan.ui.BrowserFragment;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.navigator.manager.UiManager;
import com.mishiranu.dashchan.ui.navigator.page.ListPage;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.widget.CustomSearchView;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ExpandedLayout;
import com.mishiranu.dashchan.widget.ListPosition;
import com.mishiranu.dashchan.widget.MenuExpandListener;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import com.mishiranu.dashchan.widget.ThreadQuickNavigation;
import com.mishiranu.dashchan.widget.ThreadFabMenu;
import com.mishiranu.dashchan.ui.navigator.page.PostsPage;
import com.mishiranu.dashchan.ui.navigator.page.ThreadsPage;
import com.mishiranu.dashchan.widget.ViewFactory;
import java.util.Collection;
import java.util.UUID;

public final class PageFragment extends ContentFragment implements FragmentHandler.Callback, ListPage.Callback {
	private static final String EXTRA_PAGE = "page";
	private static final String EXTRA_RETAIN_ID = "retainId";
	private static final String EXTRA_THREAD_LIST_ORIGIN = "threadListOrigin";

	private static final String EXTRA_LIST_POSITION = "listPosition";
	private static final String EXTRA_PARCELABLE_EXTRA = "parcelableExtra";
	private static final String EXTRA_INIT_ERROR_ITEM = "initErrorItem";
	private static final String EXTRA_BROWSER_CHALLENGE = "browserChallenge";
	private static final String EXTRA_SEARCH_CURRENT_QUERY = "searchCurrentQuery";
	private static final String EXTRA_SEARCH_SUBMIT_QUERY = "searchSubmitQuery";
	private static final String EXTRA_SEARCH_FOCUSED = "searchFocused";

	public interface Callback {
		UiManager getUiManager();
		ListPage.Retainable getRetainableExtra(String retainId);
		void storeRetainableExtra(String retainId, ListPage.Retainable extra);
		void setPageTitle(String title, String subtitle, String threadTitle);
		void invalidateHomeUpState();
		boolean deferUiWorkUntilDrawerIdle(Runnable runnable);
		void handleRedirect(String chanName, String boardName, String threadNumber, PostNumber postNumber);
		void closeCurrentPage();
	}

	public PageFragment() {}

	public PageFragment(Page page, String retainId) {
		Bundle args = new Bundle();
		args.putParcelable(EXTRA_PAGE, page);
		args.putString(EXTRA_RETAIN_ID, retainId);
		setArguments(args);
	}

	public Page getPage() {
		return AndroidUtils.getParcelable(requireArguments(), EXTRA_PAGE, Page.class);
	}

	public String getRetainId() {
		return requireArguments().getString(EXTRA_RETAIN_ID);
	}

	public Page getThreadListOrigin() {
		try {
			return AndroidUtils.getParcelable(requireArguments(), EXTRA_THREAD_LIST_ORIGIN, Page.class);
		} catch (BadParcelableException e) {
			return null;
		}
	}

	/** Set only on a prepared, not yet added fragment. Do not mutate a saved StackItem's Bundle. */
	public void setThreadListOrigin(Page origin) {
		Bundle args = new Bundle(requireArguments());
		args.putParcelable(EXTRA_THREAD_LIST_ORIGIN, origin);
		setArguments(args);
	}

	@Override
	public boolean isPrimaryNavigationContent() {
		return true;
	}

	private Callback getCallback() {
		return (Callback) requireActivity();
	}

	private ListPage listPage;
	private boolean destroyingView;

	public boolean canRefreshGallery() {
		return listPage instanceof com.mishiranu.dashchan.ui.navigator.page.PostsPage &&
				((com.mishiranu.dashchan.ui.navigator.page.PostsPage) listPage).canRefreshGallery();
	}

	public java.util.Set<PostNumber> getGalleryPostNumbers() {
		return canRefreshGallery() ? ((com.mishiranu.dashchan.ui.navigator.page.PostsPage) listPage)
				.getGalleryPostNumbers() : null;
	}

	public Runnable refreshGallery(com.mishiranu.dashchan.ui.gallery.GalleryRefreshCallback callback,
			java.util.Set<PostNumber> knownPosts) {
		return canRefreshGallery() ? ((com.mishiranu.dashchan.ui.navigator.page.PostsPage) listPage)
				.refreshGallery(callback, knownPosts) : null;
	}
	private View progressView;
	private ViewFactory.ErrorHolder errorHolder;
	private Button localNetworkSettingsButton;
	private LinearLayout browserChallengeActions;
	private boolean browserChallenge;
	private PaddedRecyclerView recyclerView;
	private ThreadQuickNavigation quickNavigation;
	private ThreadFabMenu threadFabMenu;
	private boolean threadContentVisible;

	private PostsPage threadFabPage() { return listPage instanceof PostsPage ? (PostsPage) listPage : null; }
	private void refreshThreadFabPreferences() {
		if (threadFabMenu == null) return;
		PostsPage posts = threadFabPage();
		boolean enabled = posts != null && posts.usesThreadFabMenu();
		threadFabMenu.setEnabledForThread(enabled);
		updateThreadFabGeometry();
	}
	private void updateThreadFabGeometry() {
		if (threadFabMenu == null || recyclerView == null) return;
		PostsPage posts = threadFabPage();
		boolean enabled = posts != null && posts.usesThreadFabMenu();
		Rect bounds = threadFabMenu.getFabBounds();
		if (quickNavigation != null) quickNavigation.setThreadFab(enabled, threadFabMenu.isOpen(), bounds);
	}


	/** The visible message viewport, in root coordinates, excluding toolbar/system padding. */
	public Rect getThreadMotionContentBounds() {
		View root = getView();
		if (root == null || recyclerView == null || recyclerView.getChildCount() == 0
				|| recyclerView.getVisibility() != View.VISIBLE || recyclerView.getAlpha() <= 0f) return null;
		int[] rootLocation = new int[2], listLocation = new int[2];
		root.getLocationInWindow(rootLocation);
		recyclerView.getLocationInWindow(listLocation);
		int left = listLocation[0] - rootLocation[0], top = listLocation[1] - rootLocation[1];
		Rect viewport = new Rect(left + recyclerView.getPaddingLeft(), top + recyclerView.getPaddingTop(),
				left + recyclerView.getWidth() - recyclerView.getPaddingRight(),
				top + recyclerView.getHeight() - recyclerView.getPaddingBottom());
		return viewport.intersect(0, 0, root.getWidth(), root.getHeight()) && !viewport.isEmpty()
				? viewport : null;
	}

	/** Draw wallpaper and messages only; later siblings (progress, errors, arrows) stay out of the morph. */
	public void drawThreadMotionContent(Canvas canvas) {
		View root = getView();
		if (!(root instanceof ViewGroup) || recyclerView == null) return;
		if (root.getBackground() != null) root.getBackground().draw(canvas);
		ViewGroup group = (ViewGroup) root;
		for (int i = 0; i < group.getChildCount(); i++) {
			View child = group.getChildAt(i);
			if (child.getVisibility() == View.VISIBLE && child.getAlpha() > 0f) {
				int save = canvas.save();
				canvas.translate(child.getLeft() - group.getScrollX(), child.getTop() - group.getScrollY());
				canvas.concat(child.getMatrix());
				if (child.getAlpha() < 1f) {
					canvas.saveLayerAlpha(0, 0, child.getWidth(), child.getHeight(),
							Math.round(255f * child.getAlpha()));
				}
				if (child == recyclerView) recyclerView.drawContentSnapshot(canvas);
				else child.draw(canvas);
				canvas.restoreToCount(save);
			}
			if (child == recyclerView) break;
		}
	}

	/** Resolve the currently bound visible card, never a saved adapter position or old rectangle. */
	public View findThreadMotionCard(ThreadMotionKey key) {
		if (recyclerView != null) {
			for (int i = 0; i < recyclerView.getChildCount(); i++) {
				View child = recyclerView.getChildAt(i);
				if (key.equals(child.getTag(R.id.thread_motion_key))) return child;
			}
		}
		return null;
	}

	private CustomSearchView searchView;
	private MenuItem searchMenuItem;

	private String actionBarLockerPull;
	private String actionBarLockerSearch;

	private ListPosition listPosition;
	private Parcelable parcelableExtra;
	private ErrorItem initErrorItem;
	private String searchCurrentQuery;
	private String searchSubmitQuery;
	private boolean searchFocused;

	private ListPage.InitRequest initRequest;
	private boolean resetScroll = false;

	private boolean allowShowScale;
	private OneShotPreDrawListener listRevealListener;
	private Runnable doOnResume;
	private boolean searchMode = false;
	private long searchBackRevision;
	private boolean predictiveSearchCommit;
	public View getPredictiveSearchView() { return searchMode ? searchView : null; }
	public long getSearchBackRevision() { return searchBackRevision; }
	@Override public long getBackActionRevision() { return searchBackRevision; }
	public boolean commitPredictiveSearchBack(View expected, long revision) {
		if (!searchMode || searchView != expected || searchBackRevision != revision) return false;
		predictiveSearchCommit = true;
		try { return setSearchMode(false); } finally { predictiveSearchCommit = false; }
	}
	private boolean saveToStack = false;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		listPosition = savedInstanceState != null && !resetScroll
				? AndroidUtils.getParcelable(savedInstanceState, EXTRA_LIST_POSITION, ListPosition.class) : null;
		if (savedInstanceState != null) {
			try {
				parcelableExtra = AndroidUtils.getParcelable(savedInstanceState,
						EXTRA_PARCELABLE_EXTRA, Parcelable.class);
			} catch (BadParcelableException e) {
				// Saved page state may come from an older application version whose custom Parcelable
				// layout is shorter. Discard only this transient UI state and rebuild the page instead of
				// failing the whole activity restoration on strict Android versions.
				savedInstanceState.remove(EXTRA_PARCELABLE_EXTRA);
				parcelableExtra = null;
			}
		} else {
			parcelableExtra = null;
		}
		initErrorItem = savedInstanceState != null
				? AndroidUtils.getParcelable(savedInstanceState, EXTRA_INIT_ERROR_ITEM, ErrorItem.class) : null;
		browserChallenge = savedInstanceState != null && savedInstanceState.getBoolean(EXTRA_BROWSER_CHALLENGE);
		searchCurrentQuery = savedInstanceState != null ? savedInstanceState
				.getString(EXTRA_SEARCH_CURRENT_QUERY) : null;
		searchSubmitQuery = savedInstanceState != null ? savedInstanceState
				.getString(EXTRA_SEARCH_SUBMIT_QUERY) : null;
		searchFocused = savedInstanceState != null && savedInstanceState.getBoolean(EXTRA_SEARCH_FOCUSED);
		resetScroll = false;
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		destroyingView = false;
		actionBarLockerPull = "pull-" + UUID.randomUUID();
		actionBarLockerSearch = "search-" + UUID.randomUUID();

		ExpandedLayout layout = new ExpandedLayout(container.getContext(), false, getPage().content == Page.Content.POSTS);
		recyclerView = new PaddedRecyclerView(layout.getContext());
		layout.addView(recyclerView, ExpandedLayout.LayoutParams.MATCH_PARENT,
				ExpandedLayout.LayoutParams.MATCH_PARENT);
		WallpaperManager.applyToPage(layout, recyclerView);
		layout.setRecyclerView(recyclerView);
		recyclerView.setMotionEventSplittingEnabled(false);
		recyclerView.setVerticalScrollBarEnabled(true);
		recyclerView.setClipToPadding(false);
		recyclerView.setFastScrollerEnabled(Preferences.isActiveScrollbar());
		boolean standardService = getPage().content == Page.Content.HISTORY || getPage().content == Page.Content.BOARDS
				|| getPage().content == Page.Content.USER_BOARDS;
		standardService |= getPage().content == Page.Content.MY_POSTS || getPage().content == Page.Content.ARCHIVE;
		progressView = ViewFactory.createProgressLayout(layout, standardService);
		// The same bounded service-page registry owns loading, error and page appearance.
		errorHolder = ViewFactory.createErrorLayout(layout, standardService);
		if (isSoyjakPage()) {
			browserChallengeActions = new LinearLayout(layout.getContext());
			browserChallengeActions.setOrientation(LinearLayout.VERTICAL);
			browserChallengeActions.setVisibility(View.GONE);
			((ViewGroup) errorHolder.text.getParent()).addView(browserChallengeActions,
					ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			addBrowserChallengeButton(R.string.soyjak_open_page, this::openSoyjakPage);
			addBrowserChallengeButton(R.string.retry, this::retrySoyjakPage);
		}
		if (standardService) com.mishiranu.dashchan.widget.InterfaceAppearance.standardPage(layout);
		errorHolder.layout.setVisibility(View.GONE);
		layout.addView(errorHolder.layout);
		if (getPage().content == Page.Content.POSTS || getPage().content == Page.Content.THREADS
				|| getPage().content == Page.Content.COMBINED_THREADS) {
			quickNavigation = new ThreadQuickNavigation(recyclerView, true);
			layout.addView(quickNavigation, ExpandedLayout.LayoutParams.MATCH_PARENT,
					ExpandedLayout.LayoutParams.MATCH_PARENT);
		}

		if (getPage().content == Page.Content.POSTS) {
			threadFabMenu = new ThreadFabMenu(recyclerView, new ThreadFabMenu.Host() {
				@Override public boolean isAvailable() {
					PostsPage posts = threadFabPage();
					return isResumed() && threadContentVisible && !searchMode && posts != null && posts.canShowThreadFabMenu()
							&& !((FragmentHandler) requireActivity()).isContentObscured();
				}
				@Override public ThreadFabMenu.Action getSingleAction() { return threadFabPage().getSingleThreadFabAction(); }
				@Override public java.util.List<ThreadFabMenu.Action> getActions() { return threadFabPage().getThreadFabActions(); }
				@Override public void onAction(int id) {
					PostsPage posts = threadFabPage();
					if (posts != null && posts.canShowThreadFabMenu()) posts.performThreadFabAction(id);
				}
				@Override public void onOpenChanged() {
					searchBackRevision++;
					updateThreadFabGeometry();
					notifyBackNavigationChanged();
				}
				@Override public void onGeometryChanged(Rect bounds) { updateThreadFabGeometry(); }
				@Override public View getToolbar() { return ((FragmentHandler) requireActivity()).getToolbarView(); }
			});
			layout.addView(threadFabMenu, ExpandedLayout.LayoutParams.MATCH_PARENT, ExpandedLayout.LayoutParams.MATCH_PARENT);
		}

		allowShowScale = true;
		listPage = getPage().content.newPage();
		recyclerView.getPullable().setOnPullListener(listPage);
		// Thread refresh/loading must not reveal or pin the toolbar. Its scroll policy
		// and explicit search/drawer/action-mode owners still control visibility.
		recyclerView.getPullable().setPullStateListener((wrapper, busy) -> ((FragmentHandler) requireActivity())
				.setActionBarLocked(actionBarLockerPull, busy && getPage().content != Page.Content.POSTS));
		return layout;
	}

	@Override
	public void onDestroyView() {
		destroyingView = true;
		if (threadFabMenu != null) threadFabMenu.dispose();
		doOnResume = null;
		clearPendingListReveal();
		InterfaceMotion.cancel(recyclerView);
		super.onDestroyView();

		FragmentHandler fragmentHandler = (FragmentHandler) requireActivity();
		fragmentHandler.setActionBarLocked(actionBarLockerPull, false);
		fragmentHandler.setActionBarLocked(actionBarLockerSearch, false);

		if (listPage != null) {
			listPage.destroy();
			listPage = null;
		}
		progressView = null;
		errorHolder = null;
		localNetworkSettingsButton = null;
		recyclerView = null;
		quickNavigation = null;
		threadFabMenu = null;
		threadContentVisible = false;
		searchView = null;
		searchMenuItem = null;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		ErrorItem initErrorItem = this.initErrorItem;
		this.initErrorItem = null;
		ListPage.InitRequest initRequest = this.initRequest;
		this.initRequest = null;
		if (initRequest == null && initErrorItem != null) {
			initRequest = new ListPage.InitRequest(initErrorItem);
		}
		if (isSoyjakPage()) {
			SoyjakReadRecovery.Result recovered = getSoyjakRecovery().consume(getRetainId(), getPage());
			if (recovered != null) {
				initRequest = ListPage.InitRequest.recovered(recovered, initRequest);
				setBrowserChallenge(false);
			}
		}
		listPage.init(getPage(), this, this, recyclerView, listPosition, getCallback().getUiManager(),
				getCallback().getRetainableExtra(getRetainId()), parcelableExtra, initRequest,
				new ListPage.InitSearch(searchCurrentQuery, searchSubmitQuery));
		notifyTitleChanged();
	}

	@Override
	public void onResume() {
		super.onResume();

		if (listPage == null) return;
		listPage.resume();
		if (quickNavigation != null) quickNavigation.refreshPreferences();
		refreshThreadFabPreferences();
		if (threadFabMenu != null) threadFabMenu.setActive(true);
		Runnable doOnResume = this.doOnResume;
		this.doOnResume = null;
		if (doOnResume != null && listPage != null && listPage.isRunning()) {
			doOnResume.run();
		}
	}

	@Override
	public void onPause() {
		if (threadFabMenu != null) threadFabMenu.setActive(false);
		super.onPause();

		if (listPage != null) {
			listPage.pause();
		}
	}

	public void setSaveToStack(boolean saveToStack) {
		this.saveToStack = saveToStack;
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);

		if (listPage != null) {
			listPosition = listPage.getListPosition();
			Pair<ListPage.Retainable, Parcelable> extraPair = listPage.getExtraToStore(saveToStack);
			getCallback().storeRetainableExtra(getRetainId(), extraPair.first);
			parcelableExtra = extraPair.second;
			CustomSearchView searchView = getSearchView(false);
			if (searchView != null) {
				searchFocused = searchView.isSearchFocused();
			}
		}
		outState.putParcelable(EXTRA_LIST_POSITION, listPosition);
		outState.putParcelable(EXTRA_PARCELABLE_EXTRA, parcelableExtra);
		if (initErrorItem != null && (!saveToStack || browserChallenge)) {
			outState.putParcelable(EXTRA_INIT_ERROR_ITEM, initErrorItem);
		}
		outState.putBoolean(EXTRA_BROWSER_CHALLENGE, browserChallenge);
		outState.putString(EXTRA_SEARCH_CURRENT_QUERY, searchCurrentQuery);
		outState.putString(EXTRA_SEARCH_SUBMIT_QUERY, searchSubmitQuery);
		outState.putBoolean(EXTRA_SEARCH_FOCUSED, searchFocused && !saveToStack);
	}

	@Override
	public void onTerminate() {
		doOnResume = null;
		clearPendingListReveal();
		super.onTerminate();

		if (listPage != null) {
			listPage.destroy();
			listPage = null;
		}
	}

	@Override
	public void onChansChanged(Collection<String> changed, Collection<String> removed) {
		Page page = getPage();
		if (changed.contains(page.chanName)) {
			invalidateOptionsMenu();
		}
	}

	private CustomSearchView getSearchView(boolean required) {
		if (searchView == null && required) {
			searchView = obtainSearchView();
			searchView.setOnSubmitListener(query -> {
				if (listPage.onSearchSubmit(query)) {
					searchSubmitQuery = null;
					setSearchMode(false);
					return true;
				} else {
					searchSubmitQuery = query;
					return false;
				}
			});
			searchView.setOnChangeListener(query -> {
				searchBackRevision++;
				if (listPage != null) {
					listPage.onSearchQueryChange(query);
				}
				if (searchCurrentQuery != null) {
					searchCurrentQuery = query;
				}
			});
		}
		return searchView;
	}

	private boolean setSearchMode(boolean search, boolean toggle) {
		MenuItem menuItem = searchMenuItem;
		if (menuItem != null && searchMode != search) {
			searchBackRevision++;
			searchMode = search;
			if (search && threadFabMenu != null) threadFabMenu.close(false, false);
			if (search) {
				CustomSearchView searchView = getSearchView(true);
				searchView.setHint(menuItem.getTitle());
				listPage.onSearchQueryChange(searchView.getQuery());
			} else {
				listPage.onSearchQueryChange("");
				listPage.onSearchCancel();
			}
			invalidateOptionsMenu();
			((FragmentHandler) requireActivity()).setActionBarLocked(actionBarLockerSearch, search);
			getCallback().invalidateHomeUpState();
			if (toggle) {
				if (search) {
					menuItem.expandActionView();
				} else {
					menuItem.collapseActionView();
				}
			}
			notifyBackNavigationChanged();
			return true;
		}
		return false;
	}

	private boolean setSearchMode(boolean search) {
		return setSearchMode(search, true);
	}

	public void setInitRequest(ListPage.InitRequest initRequest) {
		this.initRequest = initRequest;
	}

	public void openCatalog() {
		if (listPage instanceof ThreadsPage && listPage.isRunning()) {
			((ThreadsPage) listPage).openCatalog();
		} else {
			setInitRequest(ListPage.InitRequest.catalog());
		}
	}

	public void requestResetScroll() {
		resetScroll = true;
	}

	public void onAppearanceOptionChanged(int what) {
		listPage.onAppearanceOptionChanged(what);
	}

	public int onDrawerNumberEntered(int number) {
		return listPage.onDrawerNumberEntered(number);
	}

	public void updatePageConfiguration(PostNumber postNumber) {
		if (listPage != null) {
			listPage.updatePageConfiguration(postNumber);
		} else {
			ListPage.InitRequest last = this.initRequest;
			initRequest = new ListPage.InitRequest(last != null && last.shouldLoad,
					postNumber, last != null ? last.threadTitle : null);
		}
	}

	public void handleNewPostDataListNow() {
		listPage.handleNewPostDataListNow();
	}

	public void scrollToPost(PostNumber postNumber) {
		listPage.handleScrollToPost(postNumber);
	}

	@Override
	public boolean isValidOptionsMenuState() {
		return listPage != null && listPage.isRunning();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, boolean primary) {
		listPage.onCreateOptionsMenu(menu);
		if (isSoyjakPage()) {
			menu.add(0, R.id.menu_soyjak_open_page, 0, R.string.soyjak_open_page).setVisible(browserChallenge);
			menu.add(0, R.id.menu_soyjak_retry, 0, R.string.retry).setVisible(browserChallenge);
			menu.add(0, R.id.menu_soyjak_diagnostics, 0, R.string.soyjak_diagnostics_title)
					.setVisible(browserChallenge);
		}
		if (primary) {
			searchBackRevision++;
			MenuItem searchMenuItem = menu.findItem(R.id.menu_search);
			this.searchMenuItem = searchMenuItem;
			if (searchMenuItem != null) {
				this.searchMenuItem = searchMenuItem;
				searchMenuItem.setActionView(getSearchView(true));
				searchMenuItem.setOnActionExpandListener(new MenuExpandListener((menuItem, expand) -> {
					if (expand) {
						searchView.setFocusOnExpand(searchFocused);
						if (searchCurrentQuery != null) {
							searchView.setQuery(searchCurrentQuery);
						} else {
							searchCurrentQuery = "";
						}
					} else {
						searchCurrentQuery = null;
						searchSubmitQuery = null;
					}
					setSearchMode(expand, false);
					return true;
				}, () -> !predictiveSearchCommit));
				if (searchCurrentQuery != null) {
					searchMenuItem.expandActionView();
				}
			}
		}
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu, boolean primary) {
		for (int i = 0; i < menu.size(); i++) {
			MenuItem menuItem = menu.getItem(i);
			if (!menuItem.isVisible() && !searchMode) {
				menuItem.setVisible(true);
			}
		}
		listPage.onPrepareOptionsMenu(menu);
		if (isSoyjakPage()) {
			boolean showActions = browserChallenge && errorHolder.layout.getVisibility() != View.VISIBLE;
			menu.findItem(R.id.menu_soyjak_open_page).setVisible(showActions);
			menu.findItem(R.id.menu_soyjak_retry).setVisible(showActions);
			menu.findItem(R.id.menu_soyjak_diagnostics).setVisible(showActions);
		}
		for (int i = 0; i < menu.size(); i++) {
			MenuItem menuItem = menu.getItem(i);
			if (menuItem.isVisible() && searchMode && menuItem.getItemId() != R.id.menu_search) {
				menuItem.setVisible(false);
			}
		}
	}

	@Override
	public boolean onMenuItemSelected(@NonNull MenuItem item) {
		if (isSoyjakPage() && browserChallenge) {
			if (item.getItemId() == R.id.menu_soyjak_open_page) {
				openSoyjakPage();
				return true;
			}
			if (item.getItemId() == R.id.menu_soyjak_retry) {
				retrySoyjakPage();
				return true;
			}
			if (item.getItemId() == R.id.menu_soyjak_diagnostics) {
				SoyjakDiagnostics.showReport(requireContext());
				return true;
			}
		}
		if (item.getItemId() == R.id.menu_search) {
			if (item == searchMenuItem) {
				searchFocused = true;
				return false;
			} else if (searchMenuItem != null) {
				searchFocused = true;
				searchMenuItem.expandActionView();
				return true;
			} else {
				return true;
			}
		}
		if (listPage.onOptionsItemSelected(item)) {
			return true;
		}
		return super.onMenuItemSelected(item);
	}

	@Override
	public boolean onSearchRequested() {
		return setSearchMode(true) || searchMode;
	}

	@Override
	public boolean onBackPressed() {
		return threadFabMenu != null && threadFabMenu.close(true, true) || setSearchMode(false);
	}

	@Override
	public boolean canHandleBack() {
		return threadFabMenu != null && threadFabMenu.isOpen() || searchMode;
	}

	@Override
	public void notifyTitleChanged() {
		Pair<String, String> titleSubtitle = listPage.obtainTitleSubtitle();
		String title = titleSubtitle != null ? titleSubtitle.first : null;
		String subtitle = titleSubtitle != null ? titleSubtitle.second : null;
		String threadTitle = null;
		if (getPage().content == Page.Content.POSTS) {
			threadTitle = listPage.obtainTitle();
			if (Preferences.isHideThreadTitle()) {
				title = null;
				subtitle = null;
			}
		}
		getCallback().setPageTitle(title, subtitle, threadTitle);
	}

	@Override
	public void setCustomSearchView(View view) {
		if (view == null) { clearCustomSearchView(); return; }
		if (destroyingView || !isAdded()) return;
		getSearchView(true).setCustomView(view);
	}

	@Override
	public void clearSearchFocus() {
		CustomSearchView searchView = getSearchView(false);
		if (searchView != null) {
			searchView.clearFocus();
		}
	}

	@Override
	public Context getToolbarContext() {
		return ((FragmentHandler) requireActivity()).getToolbarContext();
	}

	@Override
	public ActionMode startActionMode(ActionMode.Callback callback) {
		if (threadFabMenu != null) threadFabMenu.close(false, false);
		return requireActivity().startActionMode(callback);
	}

	@Override
	public boolean deferUiWorkUntilDrawerIdle(Runnable runnable) {
		return getCallback().deferUiWorkUntilDrawerIdle(runnable);
	}

	@Override
	public void switchList() {
		threadContentVisible = true;
		ContentStateMotion.Session motion = prepareStateMotion(recyclerView);
		if (quickNavigation != null) quickNavigation.setContentVisible(true);
		initErrorItem = null;
		progressView.setVisibility(View.GONE);
		errorHolder.layout.setVisibility(View.GONE);
		if (motion != null) motion.commit();
	}

	@Override
	public void switchProgress() {
		setBrowserChallenge(false);
		threadContentVisible = false;
		if (threadFabMenu != null) threadFabMenu.close(false, false);
		ContentStateMotion.Session motion = prepareStateMotion(progressView);
		if (quickNavigation != null) quickNavigation.setContentVisible(false);
		initErrorItem = null;
		progressView.setVisibility(View.VISIBLE);
		errorHolder.layout.setVisibility(View.GONE);
		if (motion != null) motion.commit();
	}

	@Override
	public void switchError(ErrorItem errorItem) {
		threadContentVisible = false;
		if (threadFabMenu != null) threadFabMenu.close(false, false);
		ContentStateMotion.Session motion = prepareStateMotion(errorHolder.layout);
		if (quickNavigation != null) quickNavigation.setContentVisible(false);
		if (errorItem == null) {
			errorItem = new ErrorItem(ErrorItem.Type.UNKNOWN);
		}
		initErrorItem = errorItem;
		progressView.setVisibility(View.GONE);
		errorHolder.layout.setVisibility(View.VISIBLE);
		errorHolder.text.setText(errorItem.toString());
		boolean localNetworkError = errorItem.type == ErrorItem.Type.LOCAL_NETWORK_PERMISSION
				&& getPage().chanName != null;
		if (localNetworkError && localNetworkSettingsButton == null) {
			ViewGroup parent = (ViewGroup) errorHolder.text.getParent();
			localNetworkSettingsButton = new Button(parent.getContext(), null, android.R.attr.borderlessButtonStyle);
			localNetworkSettingsButton.setText(R.string.local_network_access);
			localNetworkSettingsButton.setOnClickListener(v -> {
				if (isResumed()) ((FragmentHandler) requireActivity()).pushFragment(
						new com.mishiranu.dashchan.ui.preference.ChanFragment(getPage().chanName));
			});
			parent.addView(localNetworkSettingsButton, ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
		}
		if (localNetworkSettingsButton != null) {
			localNetworkSettingsButton.setVisibility(localNetworkError ? View.VISIBLE : View.GONE);
		}
		setBrowserChallenge(errorItem.type == ErrorItem.Type.BROWSER_CHALLENGE);
		if (browserChallengeActions != null) {
			browserChallengeActions.setVisibility(browserChallenge ? View.VISIBLE : View.GONE);
		}
		if (motion != null) motion.commit();
	}

	@Override
	public boolean showBrowserChallenge(ErrorItem errorItem) {
		if (!isSoyjakPage() || errorItem.type != ErrorItem.Type.BROWSER_CHALLENGE) return false;
		SoyjakDiagnostics.event("ui_challenge_shown", null);
		setBrowserChallenge(true);
		ClickableToast.show(errorItem.toString(), null,
				new ClickableToast.Button(R.string.soyjak_open_page, true, this::openSoyjakPage));
		return true;
	}

	@Override
	public void clearBrowserChallenge() { setBrowserChallenge(false); }

	private boolean isSoyjakPage() {
		Page page = getPage();
		return "soyjak".equals(page.chanName) && (page.content == Page.Content.BOARDS
				|| page.content == Page.Content.THREADS || page.content == Page.Content.POSTS);
	}

	private void setBrowserChallenge(boolean value) {
		if (browserChallenge != value) {
			browserChallenge = value;
			invalidateOptionsMenu();
		}
	}

	private void addBrowserChallengeButton(int title, Runnable action) {
		Button button = new Button(browserChallengeActions.getContext(), null, android.R.attr.borderlessButtonStyle);
		button.setText(title);
		button.setOnClickListener(v -> action.run());
		browserChallengeActions.addView(button, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
	}

	private void retrySoyjakPage() {
		if (isResumed() && listPage != null && listPage.isRunning()) listPage.retryBrowserChallenge();
	}

	private SoyjakReadRecovery getSoyjakRecovery() {
		return new ViewModelProvider(requireActivity()).get(SoyjakReadRecovery.class);
	}

	private void openSoyjakPage() {
		if (!isResumed() || !isSoyjakPage() || !browserChallenge || getParentFragmentManager().isStateSaved()) return;
		Page page = getPage();
		SoyjakDiagnostics.event("ui_open_page", "content=" + page.content.name());
		ChanLocator locator = Chan.get(page.chanName).locator;
		ThreadsPage.BrowserChallengeRequest challenge = listPage instanceof ThreadsPage threadsPage
				? threadsPage.getBrowserChallengeRequest() : null;
		int pageNumber = challenge != null ? challenge.pageNumber() : ChanPerformer.ReadThreadsData.PAGE_NUMBER_CATALOG;
		boolean append = challenge != null && challenge.append();
		Uri uri = page.threadNumber != null ? locator.safe(false).createThreadUri(page.boardName, page.threadNumber)
				: page.boardName != null ? locator.safe(false).createBoardUri(page.boardName, pageNumber) : locator.buildPath();
		if (uri == null) {
			ClickableToast.show(R.string.unknown_address);
			return;
		}
		SoyjakReadRecovery.Request request = getSoyjakRecovery().start(getRetainId(), page, pageNumber, append, uri);
		if (request != null) {
			((FragmentHandler) requireActivity()).pushFragment(BrowserFragment.forSoyjakRecovery(request));
		}
	}

	private ContentStateMotion.Session prepareStateMotion(View incoming) {
		clearPendingListReveal();
		View root = getView();
		View outgoing = errorHolder.layout.getVisibility() == View.VISIBLE ? errorHolder.layout
				: progressView.getVisibility() == View.VISIBLE ? progressView : recyclerView;
		return root instanceof ViewGroup ? ContentStateMotion.prepare((ViewGroup) root, outgoing, incoming) : null;
	}

	private void clearPendingListReveal() {
		if (listRevealListener != null) {
			listRevealListener.removeListener();
			listRevealListener = null;
		}
	}

	@Override
	public void showScaleAnimation() {
		if (allowShowScale) {
			allowShowScale = false;
			if (InterfaceMotion.isEnabled()) {
				View target = recyclerView;
				View root = getView();
				clearPendingListReveal();
				listRevealListener = ContentStateMotion.revealBeforeDraw(target, root, () ->
						target == recyclerView && root != null && root == getView() && listPage != null &&
						InterfaceMotion.isEnabled());
			} else {
				createAnimator(recyclerView, true).start();
			}
		}
	}

	@Override
	public void handleRedirect(String chanName, String boardName, String threadNumber, PostNumber postNumber) {
		if (isStateSaved()) {
			doOnResume = () -> handleRedirect(chanName, boardName, threadNumber, postNumber);
		} else {
			getCallback().handleRedirect(chanName, boardName, threadNumber, postNumber);
		}
	}

	@Override
	public void closePage() {
		if (isStateSaved()) {
			doOnResume = () -> getCallback().closeCurrentPage();
		} else {
			getCallback().closeCurrentPage();
		}
	}
}
