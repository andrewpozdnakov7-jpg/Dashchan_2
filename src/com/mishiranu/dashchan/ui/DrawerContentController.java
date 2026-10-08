package com.mishiranu.dashchan.ui;

import android.content.Context;
import android.content.res.TypedArray;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.util.CommonUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.storage.CombinedFeedStorage;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.content.storage.MyPostsStorage;
import com.mishiranu.dashchan.content.storage.RedditPageStorage;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.mishiranu.dashchan.ui.DrawerForm.Page;
import static com.mishiranu.dashchan.ui.DrawerForm.*;

/** Model owner; views, interaction, selection UI and adapter diff dispatch stay in DrawerForm. */
final class DrawerContentController {
	private final Context context;
	private final Supplier<String> currentChan;
	private final DrawerForm.Callback callback;
	private final Predicate<FavoritesStorage.FavoriteItem> hiddenFavorite;
	private static final int COLLAPSED_OPEN_THREAD_LIMIT = 12;
	final ArrayList<ListItem> pages = new ArrayList<>();
	final ArrayList<ListItem> pageNavigation = new ArrayList<>();
	final ArrayList<ListItem> orderedDrawerItems = new ArrayList<>();
	List<Preferences.DrawerSection> drawerSectionOrder;
	final ArrayList<Page> collapsedPages = new ArrayList<>();
	final ArrayList<RedditPageStorage.Entry> collapsedRedditPages = new ArrayList<>();
	final ArrayList<ListItem> favorites = new ArrayList<>();
	final ArrayList<ListItem> displayedFavorites = new ArrayList<>();
	final ArrayList<ListItem> menu = new ArrayList<>();
	boolean mergeChans = false;
	boolean showHistory = false;
	boolean combinedFeedsEnabled = false;
	boolean redditWebReaderEnabled = false;
	boolean trackMyPostsEnabled;
	boolean collapseLongOpenThreadsEnabled;
	boolean pagesExpanded;
	Preferences.PagesListMode pagesListMode = null;
	CategoriesOrder categoriesOrder;

	enum CategoriesOrder {PAGES_FIRST, FAVORITES_FIRST, HIDE_PAGES}

	DrawerContentController(Context context, Supplier<String> currentChan, DrawerForm.Callback callback,
			Predicate<FavoritesStorage.FavoriteItem> hiddenFavorite) {
		this.context = context;
		this.currentChan = currentChan;
		this.callback = callback;
		this.hiddenFavorite = hiddenFavorite;
	}

	void updatePageNavigation() {
		String chanName = currentChan.get();
		pageNavigation.clear();
		menu.removeIf(item -> item.type == ListItem.Type.MENU && item.data == MENU_ITEM_MY_POSTS);
		// Keep the existing forum scope: Reddit has its own drawer menu.
		if (CHAN_REDDIT.equals(chanName)) return;
		if (combinedFeedsEnabled && pagesListMode != Preferences.PagesListMode.HIDE_PAGES) {
			pageNavigation.add(new ListItem(ListItem.Type.SECTION, SECTION_ACTION_COMBINED_FEEDS_SETTINGS,
					ResourceUtils.getResourceId(context, R.attr.iconDrawerMenuPreferences, 0),
					context.getString(R.string.combined_feeds)));
			for (CombinedFeedStorage.Feed feed : CombinedFeedStorage.getInstance().getFeeds()) {
				pageNavigation.add(new ListItem(ListItem.Type.COMBINED_FEED, feed.getPrimaryChanName(),
						feed.id, null, feed.title));
			}
		}
		if (trackMyPostsEnabled) {
			int repliesPosition = 0;
			while (repliesPosition < menu.size() && (menu.get(repliesPosition).data == MENU_ITEM_BOARDS ||
					menu.get(repliesPosition).data == MENU_ITEM_USER_BOARDS)) repliesPosition++;
			menu.add(repliesPosition, new ListItem(ListItem.Type.MENU, MENU_ITEM_MY_POSTS, R.drawable.ic_reply,
					context.getString(R.string.replies), MyPostsStorage.getInstance().getUnreadCount()));
		}
	}

	void updateListPages() {
		updateListPages(callback::obtainDrawerPages, () -> RedditPageStorage.getInstance().getPages(),
				callback::getCurrentRedditPageUrl);
	}

	/** Snapshot suppliers preserve lazy access to the forum-specific source. */
	void updateListPages(Supplier<Collection<Page>> pageSource,
			Supplier<List<RedditPageStorage.Entry>> redditSource, Supplier<String> currentRedditUrl) {
		String chanName = currentChan.get();
		ArrayList<ListItem> newPages = new ArrayList<>();
		ArrayList<Page> newCollapsedPages = new ArrayList<>();
		collapsedRedditPages.clear();
		if (CHAN_REDDIT.equals(chanName)) {
			List<RedditPageStorage.Entry> redditPages = redditSource.get();
			// Keep the header available even with no pages, so communities can always be added.
			newPages.add(new ListItem(ListItem.Type.SECTION, SECTION_ACTION_CLOSE_ALL_REDDIT,
					ResourceUtils.getResourceId(context, R.attr.iconButtonCancel, 0),
					context.getString(R.string.open_pages__noun), redditPages.size()));
			String currentUrl = currentRedditUrl.get();
			int threadCount = 0;
			RedditPageStorage.Entry currentThread = null;
			for (RedditPageStorage.Entry page : redditPages) {
				if (page.type == RedditPageStorage.Type.THREAD) {
					threadCount++;
					if (page.url.equals(currentUrl)) currentThread = page;
				}
			}
			boolean collapsible = collapseLongOpenThreadsEnabled && threadCount > COLLAPSED_OPEN_THREAD_LIMIT;
			HashSet<RedditPageStorage.Entry> visibleThreads = null;
			if (collapsible && !pagesExpanded) {
				visibleThreads = new HashSet<>(COLLAPSED_OPEN_THREAD_LIMIT);
				if (currentThread != null) visibleThreads.add(currentThread);
				// Storage is ordered most recently visited first, including restored pages.
				for (RedditPageStorage.Entry page : redditPages) {
					if (page.type == RedditPageStorage.Type.THREAD && visibleThreads.size() < COLLAPSED_OPEN_THREAD_LIMIT) {
						visibleThreads.add(page);
					}
				}
			}
			for (RedditPageStorage.Entry page : redditPages) {
				if (page.type == RedditPageStorage.Type.THREAD && visibleThreads != null && !visibleThreads.contains(page)) {
					collapsedRedditPages.add(page);
				} else {
					newPages.add(new ListItem(ListItem.Type.REDDIT_PAGE, 0, CHAN_REDDIT,
							page.url, page.threadId, page.title));
				}
			}
			if (collapsible) {
				newPages.add(new ListItem(ListItem.Type.PAGES_TOGGLE, pagesExpanded ? 1 : 0,
						R.drawable.ic_arrow_drop_down, pagesExpanded
								? context.getString(R.string.collapse_open_threads)
								: context.getString(R.string.show_remaining_open_threads__format,
										threadCount - COLLAPSED_OPEN_THREAD_LIMIT)));
			} else {
				pagesExpanded = false;
			}
			this.pages.clear();
			this.pages.addAll(newPages);
			collapsedPages.clear();
			return;
		}
		boolean mergeChans = this.mergeChans;
		Collection<Page> allPages = pageSource.get();
		ArrayList<Page> pages = new ArrayList<>();
		for (Page page : allPages) {
			if (mergeChans || page.chanName.equals(chanName)) {
				if (page.threadNumber != null || !Chan.get(page.chanName).configuration
						.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
					pages.add(page);
				}
			}
		}
		if (pages.size() > 0) {
			Collections.sort(pages);
			newPages.add(new ListItem(ListItem.Type.SECTION, SECTION_ACTION_CLOSE_ALL,
					ResourceUtils.getResourceId(context, R.attr.iconButtonCancel, 0),
					context.getString(R.string.open_pages__noun)));
			int threadCount = 0;
			Page currentThread = null;
			for (Page page : pages) {
				if (page.threadNumber != null) {
					threadCount++;
					if (page.current) {
						currentThread = page;
					}
				}
			}
			boolean collapsible = collapseLongOpenThreadsEnabled
					&& threadCount > COLLAPSED_OPEN_THREAD_LIMIT;
			HashSet<Page> visibleThreads = null;
			if (collapsible && !pagesExpanded) {
				visibleThreads = new HashSet<>(COLLAPSED_OPEN_THREAD_LIMIT);
				if (currentThread != null) {
					visibleThreads.add(currentThread);
				}
				for (Page page : pages) {
					if (page.threadNumber != null && visibleThreads.size() < COLLAPSED_OPEN_THREAD_LIMIT) {
						visibleThreads.add(page);
					}
				}
			}
			for (Page page : pages) {
				if (page.threadNumber != null && visibleThreads != null && !visibleThreads.contains(page)) {
					newCollapsedPages.add(page);
					continue;
				}
				if (page.threadNumber != null) {
					newPages.add(new ListItem(ListItem.Type.PAGE, 0, page.chanName, page.boardName,
							page.threadNumber, page.threadTitle));
				} else {
					newPages.add(new ListItem(ListItem.Type.PAGE, 0, page.chanName, page.boardName,
							null, Chan.get(page.chanName).configuration.getBoardTitle(page.boardName)));
				}
			}
			if (collapsible) {
				int hiddenCount = threadCount - COLLAPSED_OPEN_THREAD_LIMIT;
				newPages.add(new ListItem(ListItem.Type.PAGES_TOGGLE, pagesExpanded ? 1 : 0,
						R.drawable.ic_arrow_drop_down, pagesExpanded
								? context.getString(R.string.collapse_open_threads)
								: context.getString(R.string.show_remaining_open_threads__format, hiddenCount)));
			} else {
				pagesExpanded = false;
			}
		}
		// Build the category off to the side and publish it in one step. Calls reached while page metadata is
		// being resolved must never interleave writes into the adapter's live list and duplicate a section.
		this.pages.clear();
		this.pages.addAll(newPages);
		collapsedPages.clear();
		collapsedPages.addAll(newCollapsedPages);
	}

	void updateListFavorites() {
		String chanName = currentChan.get();
		FavoritesStorage favoritesStorage = FavoritesStorage.getInstance();
		updateListFavorites(favoritesStorage.getBoards(mergeChans ? null : chanName),
				favoritesStorage.getThreads(mergeChans || chanName == null ? null : chanName),
				Preferences.isFavoritesHidedDeleted(), Preferences.isFavoriteThreadsCollapsed());
	}

	/** Build from snapshots without changing persisted favorites or preferences. */
	void updateListFavorites(List<FavoritesStorage.FavoriteItem> favoriteBoards,
			List<FavoritesStorage.FavoriteItem> favoriteThreads, boolean hideDeleted, boolean collapsed) {
		String chanName = currentChan.get();
		this.favorites.clear();
		boolean mergeChans = this.mergeChans;
		boolean showAllFavoriteThreads = mergeChans || chanName == null;
		boolean addSection = true;
		for (int i = 0; i < favoriteThreads.size(); i++) {
			FavoritesStorage.FavoriteItem favoriteItem = favoriteThreads.get(i);
			Chan chan = Chan.get(favoriteItem.chanName);
			if (chan.name == null) {
				continue;
			}
			if (showAllFavoriteThreads || favoriteItem.chanName.equals(chanName)) {
				if (addSection) {
					favorites.add(new ListItem(ListItem.Type.SECTION, SECTION_ACTION_FAVORITES_MENU,
							ResourceUtils.getResourceId(context, R.attr.iconButtonMore, 0),
							context.getString(R.string.favorite_threads)));
					addSection = false;
				}
				if (!(hideDeleted && hiddenFavorite.test(favoriteItem))) {
					ListItem listItem = new ListItem(ListItem.Type.FAVORITE, 0, favoriteItem.chanName,
							favoriteItem.boardName, favoriteItem.threadNumber, favoriteItem.title);
					favorites.add(listItem);
				}
			}
		}
		addSection = true;
		for (int i = 0; i < favoriteBoards.size(); i++) {
			FavoritesStorage.FavoriteItem favoriteItem = favoriteBoards.get(i);
			Chan chan = Chan.get(favoriteItem.chanName);
			if (chan.name == null || !Preferences.isChanEnabled(favoriteItem.chanName)) {
				continue;
			}
			if (mergeChans || favoriteItem.chanName.equals(chanName)) {
				if (addSection) {
					favorites.add(new ListItem(ListItem.Type.SECTION, null, null, null,
							context.getString(R.string.favorite_boards)));
					addSection = false;
				}
				favorites.add(new ListItem(ListItem.Type.FAVORITE, 0, favoriteItem.chanName, favoriteItem.boardName,
						null, chan.configuration.getBoardTitle(favoriteItem.boardName)));
			}
		}
		updateDisplayedFavorites(collapsed);
	}

	void updateDisplayedFavorites() {
		updateDisplayedFavorites(Preferences.isFavoriteThreadsCollapsed());
	}

	void updateDisplayedFavorites(boolean collapsed) {
		int threadCount = getVisibleFavoriteThreadCount();
		displayedFavorites.clear();
		for (ListItem item : favorites) {
			if (item.type == ListItem.Type.SECTION && item.data == SECTION_ACTION_FAVORITES_MENU) {
				displayedFavorites.add(new ListItem(item.type, item.data, false, item.iconResId,
						null, null, null, context.getString(R.string.favorite_threads_count__format, threadCount),
						0, !collapsed));
			} else if (!collapsed || item.type != ListItem.Type.FAVORITE || !item.isThreadItem()) {
				displayedFavorites.add(item);
			}
		}
	}

	boolean isFavoriteThreadHidden(FavoritesStorage.FavoriteItem favoriteItem) {
		return Preferences.isFavoritesHidedDeleted() &&
				hiddenFavorite.test(favoriteItem);
	}

	int getVisibleFavoriteThreadCount() {
		int count = 0;
		for (ListItem listItem : favorites) {
			if (listItem.type == ListItem.Type.FAVORITE && listItem.isThreadItem()) count++;
		}
		return count;
	}

	void rebuildOrderedDrawerItems() {
		orderedDrawerItems.clear();
		if (drawerSectionOrder == null) {
			if (categoriesOrder == CategoriesOrder.FAVORITES_FIRST) orderedDrawerItems.addAll(displayedFavorites);
			orderedDrawerItems.addAll(pageNavigation);
			if (categoriesOrder != CategoriesOrder.HIDE_PAGES) orderedDrawerItems.addAll(pages);
			if (categoriesOrder != CategoriesOrder.FAVORITES_FIRST) orderedDrawerItems.addAll(displayedFavorites);
			orderedDrawerItems.addAll(menu);
			return;
		}
		// Split only at real section boundaries; keep headers, collapsed state and rows together.
		java.util.EnumMap<Preferences.DrawerSection, List<ListItem>> blocks =
				new java.util.EnumMap<>(Preferences.DrawerSection.class);
		blocks.put(Preferences.DrawerSection.MY_BOARDS, pageNavigation);
		if (categoriesOrder != CategoriesOrder.HIDE_PAGES) blocks.put(Preferences.DrawerSection.PAGES, pages);
		Preferences.DrawerSection favoriteSection = Preferences.DrawerSection.FAVORITE_THREADS;
		for (ListItem item : displayedFavorites) {
			if (item.type == ListItem.Type.SECTION) {
				favoriteSection = item.data == SECTION_ACTION_FAVORITES_MENU
						? Preferences.DrawerSection.FAVORITE_THREADS : Preferences.DrawerSection.FAVORITE_BOARDS;
			}
			blocks.computeIfAbsent(favoriteSection, key -> new ArrayList<>()).add(item);
		}
		for (ListItem item : menu) {
			Preferences.DrawerSection section;
			switch (item.data) {
				case MENU_ITEM_COMBINED_FEEDS: section = Preferences.DrawerSection.MY_BOARDS; break;
				case MENU_ITEM_BOARDS:
				case MENU_ITEM_REDDIT_SECTIONS: section = Preferences.DrawerSection.BOARDS; break;
				case MENU_ITEM_USER_BOARDS: section = Preferences.DrawerSection.USER_BOARDS; break;
				case MENU_ITEM_MY_POSTS: section = Preferences.DrawerSection.REPLIES; break;
				case MENU_ITEM_HISTORY: section = Preferences.DrawerSection.HISTORY; break;
				case MENU_ITEM_LOCAL_ARCHIVES: section = Preferences.DrawerSection.ARCHIVES; break;
				case MENU_ITEM_REDDIT_OFFICIAL_APP: section = Preferences.DrawerSection.REDDIT_APP; break;
				case MENU_ITEM_PREFERENCES: section = Preferences.DrawerSection.SETTINGS; break;
				default: throw new IllegalStateException("Unknown drawer menu item: " + item.data);
			}
			blocks.computeIfAbsent(section, key -> new ArrayList<>()).add(item);
		}
		for (Preferences.DrawerSection section : drawerSectionOrder) {
			List<ListItem> block = blocks.get(section);
			if (block != null) orderedDrawerItems.addAll(block);
		}
	}

	boolean removeFavoriteThreadFromList(String chanName, String boardName, String threadNumber) {
		return removeFavoriteThreadFromList(chanName, boardName, threadNumber, Preferences.isFavoriteThreadsCollapsed());
	}

	boolean removeFavoriteThreadFromList(String chanName, String boardName, String threadNumber, boolean collapsed) {
		boolean removed = false;
		for (int i = favorites.size() - 1; i >= 0; i--) {
			ListItem favorite = favorites.get(i);
			if (favorite.type == ListItem.Type.FAVORITE && favorite.isThreadItem() &&
					favorite.compare(chanName, boardName, threadNumber)) {
				favorites.remove(i);
				removed = true;
			}
		}
		if (removed) updateDisplayedFavorites(collapsed);
		return removed;
	}

	boolean updatePreferencesWithoutConfiguration() {
		boolean mergeChans = Preferences.isMergeChans();
		boolean showHistory = Preferences.isRememberHistory();
		boolean combinedFeedsEnabled = Preferences.isCombinedFeedsEnabled();
		boolean redditWebReaderEnabled = Preferences.isRedditWebReaderEnabled();
		boolean trackMyPostsEnabled = Preferences.isTrackMyPostsEnabled();
		boolean collapseLongOpenThreadsEnabled = Preferences.isCollapseLongOpenThreadsEnabled();
		Preferences.PagesListMode pagesListMode = Preferences.getPagesListMode();
		List<Preferences.DrawerSection> drawerSectionOrder = Preferences.isDrawerCustomOrderEnabled()
				? Preferences.getDrawerSectionOrder() : null;
		if (this.mergeChans != mergeChans || this.showHistory != showHistory ||
				this.combinedFeedsEnabled != combinedFeedsEnabled ||
				this.redditWebReaderEnabled != redditWebReaderEnabled ||
				this.trackMyPostsEnabled != trackMyPostsEnabled ||
				this.collapseLongOpenThreadsEnabled != collapseLongOpenThreadsEnabled ||
				this.pagesListMode != pagesListMode ||
				!CommonUtils.equals(this.drawerSectionOrder, drawerSectionOrder)) {
			this.drawerSectionOrder = drawerSectionOrder;
			this.mergeChans = mergeChans;
			this.showHistory = showHistory;
			this.combinedFeedsEnabled = combinedFeedsEnabled;
			this.redditWebReaderEnabled = redditWebReaderEnabled;
			this.trackMyPostsEnabled = trackMyPostsEnabled;
			this.collapseLongOpenThreadsEnabled = collapseLongOpenThreadsEnabled;
			this.pagesExpanded = false;
			this.pagesListMode = pagesListMode;
			return true;
		}
		return false;
	}

	void updateItems(boolean pages, boolean favorites) {
		updatePageNavigation();
		if (pages && pagesListMode != Preferences.PagesListMode.HIDE_PAGES) {
			updateListPages();
		}
		if (favorites) {
			updateListFavorites();
		}
		if (pagesListMode == null) {
			categoriesOrder = null;
		} else {
			switch (pagesListMode) {
				case PAGES_FIRST: {
					categoriesOrder = CategoriesOrder.PAGES_FIRST;
					break;
				}
				case FAVORITES_FIRST: {
					categoriesOrder = CategoriesOrder.FAVORITES_FIRST;
					break;
				}
				case HIDE_PAGES: {
					categoriesOrder = CategoriesOrder.HIDE_PAGES;
					break;
				}
				default: {
					throw new IllegalStateException();
				}
			}
		}
	}

	void updateMenu(String chanName) {
		if (CHAN_REDDIT.equals(chanName)) {
			Context context = this.context;
			menu.clear();
			TypedArray typedArray = context.obtainStyledAttributes(new int[] {
					R.attr.iconDrawerMenuBoards, R.attr.iconDrawerMenuPreferences});
			int sectionIcon = typedArray.getResourceId(0, 0);
			int preferencesIcon = typedArray.getResourceId(1, 0);
			menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_REDDIT_SECTIONS, sectionIcon,
					context.getString(R.string.reddit_sections)));
			menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_REDDIT_OFFICIAL_APP, R.drawable.ic_reddit,
					context.getString(R.string.reddit_official_app)));
			menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_PREFERENCES, preferencesIcon,
					context.getString(R.string.preferences)));
			typedArray.recycle();
			return;
		}
		Chan chan = Chan.get(chanName);
		menu.clear();
		Context context = this.context;
		TypedArray typedArray = context.obtainStyledAttributes(new int[] {R.attr.iconDrawerMenuBoards,
				R.attr.iconDrawerMenuUserBoards, R.attr.iconDrawerMenuHistory,
				R.attr.iconDrawerMenuLocalArchives, R.attr.iconDrawerMenuPreferences});
		boolean hasUserBoards = chan.configuration.getOption(ChanConfiguration.OPTION_READ_USER_BOARDS);
		if (chanName != null && !chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
			menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_BOARDS, typedArray.getResourceId(0, 0),
					context.getString(hasUserBoards ? R.string.general_boards : R.string.boards)));
		}
		if (chanName != null && hasUserBoards) {
			menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_USER_BOARDS, typedArray.getResourceId(1, 0),
					context.getString(R.string.user_boards)));
		}
		if (chanName != null && Preferences.isRememberHistory()) {
			menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_HISTORY, typedArray.getResourceId(2, 0),
					context.getString(R.string.history)));
		}
		menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_LOCAL_ARCHIVES, typedArray.getResourceId(3, 0),
				context.getString(R.string.local_archives)));
		menu.add(new ListItem(ListItem.Type.MENU, MENU_ITEM_PREFERENCES, typedArray.getResourceId(4, 0),
				context.getString(R.string.preferences)));
		typedArray.recycle();
	}

	static class ListItem {
		public enum Type {HEADER, RESTART, SECTION, COMBINED_FEED, PAGE, REDDIT_PAGE,
			PAGES_TOGGLE, FAVORITE, MENU, CHAN}

		public static final ListItem HEADER = new ListItem(Type.HEADER, null, null, null, null);
		public static final ListItem RESTART = new ListItem(Type.RESTART, null, null, null, null);

		public final long id;
		public final Type type;
		public final int data;
		public final boolean iconChan;
		public final int iconResId;
		public final String chanName;
		public final String boardName;
		public final String threadNumber;
		public final String title;
		public final int badgeCount;
		public final boolean expanded;

		private static final long ID_HASH_OFFSET = 0xcbf29ce484222325L;
		private static final long ID_HASH_PRIME = 0x100000001b3L;

		private static long appendIdHash(long hash, int value) {
			for (int i = 0; i < Integer.BYTES; i++) {
				hash = (hash ^ value & 0xffL) * ID_HASH_PRIME;
				value >>>= 8;
			}
			return hash;
		}

		private static long appendIdHash(long hash, String value) {
			if (value != null) {
				for (int i = 0; i < value.length(); i++) {
					hash = (hash ^ value.charAt(i)) * ID_HASH_PRIME;
				}
			}
			return (hash ^ 0xffL) * ID_HASH_PRIME;
		}

		static long calculateId(Type type, int data,
				String chanName, String boardName, String threadNumber, String title) {
			long hash = appendIdHash(ID_HASH_OFFSET, type.ordinal());
			switch (type) {
				case COMBINED_FEED:
				case PAGE:
				case REDDIT_PAGE:
				case FAVORITE: {
					hash = appendIdHash(hash, chanName);
					hash = appendIdHash(hash, boardName);
					return appendIdHash(hash, threadNumber);
				}
				case CHAN: {
					return appendIdHash(hash, chanName);
				}
				case PAGES_TOGGLE: {
					return hash;
				}
				case SECTION: {
					hash = appendIdHash(hash, data);
					if (data == SECTION_ACTION_FAVORITES_MENU) return hash;
					return appendIdHash(hash, title);
				}
				case MENU: {
					return appendIdHash(hash, data);
				}
				case HEADER:
				case RESTART: {
					return hash;
				}
				default: {
					throw new IllegalStateException();
				}
			}
		}

		private ListItem(Type type, int data, boolean iconChan, int iconResId,
				String chanName, String boardName, String threadNumber, String title, int badgeCount) {
			this(type, data, iconChan, iconResId, chanName, boardName, threadNumber, title, badgeCount, true);
		}

		private ListItem(Type type, int data, boolean iconChan, int iconResId,
				String chanName, String boardName, String threadNumber, String title, int badgeCount, boolean expanded) {
			id = calculateId(type, data, chanName, boardName, threadNumber, title);
			this.type = type;
			this.data = data;
			this.iconChan = iconChan;
			this.iconResId = iconResId;
			this.chanName = chanName;
			this.boardName = boardName;
			this.threadNumber = threadNumber;
			this.title = title;
			this.badgeCount = badgeCount;
			this.expanded = expanded;
		}

		public ListItem(Type type, int data, int iconResId,
				String chanName, String boardName, String threadNumber, String title) {
			this(type, data, false, iconResId, chanName, boardName, threadNumber, title, 0);
		}

		public ListItem(Type type, int data, String chanName, String boardName, String threadNumber, String title) {
			this(type, data, true, 0, chanName, boardName, threadNumber, title, 0);
		}

		public ListItem(Type type, String chanName, String boardName, String threadNumber, String title) {
			this(type, 0, 0, chanName, boardName, threadNumber, title);
		}

		public ListItem(Type type, int data, int iconResId, String title) {
			this(type, data, false, iconResId, null, null, null, title, 0);
		}

		public ListItem(Type type, int data, int iconResId, String title, int badgeCount) {
			this(type, data, false, iconResId, null, null, null, title, badgeCount);
		}

		public boolean isThreadItem() {
			return threadNumber != null;
		}

		public boolean contentEquals(ListItem other) {
			return other != null && type == other.type && data == other.data && iconChan == other.iconChan &&
					iconResId == other.iconResId && CommonUtils.equals(chanName, other.chanName) &&
					CommonUtils.equals(boardName, other.boardName) &&
					CommonUtils.equals(threadNumber, other.threadNumber) && CommonUtils.equals(title, other.title)
					&& badgeCount == other.badgeCount && expanded == other.expanded;
		}

		public boolean compare(String chanName, String boardName, String threadNumber) {
			return CommonUtils.equals(this.chanName, chanName) && CommonUtils.equals(this.boardName, boardName)
					&& CommonUtils.equals(this.threadNumber, threadNumber);
		}
	}
}
