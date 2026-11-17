package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.DrawerContentController.CategoriesOrder;
import com.mishiranu.dashchan.ui.DrawerContentController.ListItem;
import java.util.Arrays;
import org.junit.Test;

public class DrawerContentControllerTest {
	private DrawerContentController model() {
		return new DrawerContentController(null, () -> "dvach", null, item -> false);
	}
	private ListItem item(ListItem.Type type, int data, String title) {
		return new ListItem(type, data, 0, title);
	}
	@Test public void identityDoesNotDependOnTitleOrBadge() {
		ListItem first = new ListItem(ListItem.Type.FAVORITE, 0, "dvach", "b", "123", "old");
		ListItem second = new ListItem(ListItem.Type.FAVORITE, 0, "dvach", "b", "123", "new");
		assertEquals(first.id, second.id);
		assertFalse(first.contentEquals(second));
		assertTrue(first.compare("dvach", "b", "123"));
		assertFalse(first.compare("dvach", "b", "124"));
		ListItem header = item(ListItem.Type.SECTION, DrawerForm.SECTION_ACTION_FAVORITES_MENU, "a");
		assertEquals(header.id, new ListItem(ListItem.Type.SECTION,
				DrawerForm.SECTION_ACTION_FAVORITES_MENU, 0, "b", 5).id);
	}
	@Test public void legacyOrderAndHidePagesPreserveOtherSections() {
		DrawerContentController model = model();
		ListItem page = item(ListItem.Type.PAGE, 0, "page");
		ListItem favorite = item(ListItem.Type.FAVORITE, 0, "favorite");
		ListItem boards = item(ListItem.Type.MENU, DrawerForm.MENU_ITEM_BOARDS, "boards");
		model.pages.add(page);
		model.displayedFavorites.add(favorite);
		model.menu.add(boards);
		model.categoriesOrder = CategoriesOrder.PAGES_FIRST;
		model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(page, favorite, boards), model.orderedDrawerItems);
		model.categoriesOrder = CategoriesOrder.FAVORITES_FIRST;
		model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(favorite, page, boards), model.orderedDrawerItems);
		model.categoriesOrder = CategoriesOrder.HIDE_PAGES;
		model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(favorite, boards), model.orderedDrawerItems);
	}
	@Test public void customOrderKeepsFavoritesHeaderWithRowsAndSnapshotIndependent() {
		DrawerContentController model = model();
		ListItem header = item(ListItem.Type.SECTION, DrawerForm.SECTION_ACTION_FAVORITES_MENU, "favorites");
		ListItem favorite = new ListItem(ListItem.Type.FAVORITE, 0, "dvach", "b", "123", "thread");
		ListItem replies = item(ListItem.Type.MENU, DrawerForm.MENU_ITEM_MY_POSTS, "replies");
		ListItem settings = item(ListItem.Type.MENU, DrawerForm.MENU_ITEM_PREFERENCES, "settings");
		model.displayedFavorites.addAll(Arrays.asList(header, favorite));
		model.menu.addAll(Arrays.asList(replies, settings));
		model.drawerSectionOrder = Arrays.asList(Preferences.DrawerSection.SETTINGS,
				Preferences.DrawerSection.FAVORITE_THREADS, Preferences.DrawerSection.REPLIES);
		model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(settings, header, favorite, replies), model.orderedDrawerItems);
		java.util.ArrayList<ListItem> snapshot = new java.util.ArrayList<>(model.orderedDrawerItems);
		model.displayedFavorites.clear();
		model.rebuildOrderedDrawerItems();
		assertEquals(4, snapshot.size());
		assertEquals(Arrays.asList(settings, replies), model.orderedDrawerItems);
	}

	@Test public void legacyVisibilityHidesWholeFavoriteBlockWithoutChangingOrderOrData() {
		DrawerContentController model = model();
		ListItem pageHeader = item(ListItem.Type.SECTION, DrawerForm.SECTION_ACTION_CLOSE_ALL, "pages");
		ListItem page = item(ListItem.Type.PAGE, 0, "page");
		ListItem threadsHeader = item(ListItem.Type.SECTION, DrawerForm.SECTION_ACTION_FAVORITES_MENU, "threads");
		ListItem group = ListItem.favoriteGroup("dvach", "b", "group", true);
		ListItem thread = new ListItem(ListItem.Type.FAVORITE, 0, "dvach", "b", "123", "thread");
		ListItem boardsHeader = item(ListItem.Type.SECTION, 0, "favorite boards");
		ListItem board = new ListItem(ListItem.Type.FAVORITE, 0, "dvach", "b", null, "board");
		ListItem settings = item(ListItem.Type.MENU, DrawerForm.MENU_ITEM_PREFERENCES, "settings");
		model.pages.addAll(Arrays.asList(pageHeader, page));
		model.displayedFavorites.addAll(Arrays.asList(threadsHeader, group, thread, boardsHeader, board));
		model.menu.add(settings);
		model.hiddenDrawerSections = java.util.EnumSet.of(Preferences.DrawerSection.FAVORITE_THREADS);
		model.categoriesOrder = CategoriesOrder.FAVORITES_FIRST;
		model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(boardsHeader, board, pageHeader, page, settings), model.orderedDrawerItems);
		model.categoriesOrder = CategoriesOrder.PAGES_FIRST;
		model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(pageHeader, page, boardsHeader, board, settings), model.orderedDrawerItems);
		assertEquals(Arrays.asList(threadsHeader, group, thread, boardsHeader, board), model.displayedFavorites);
		assertEquals(Arrays.asList(pageHeader, page), model.pages);
		model.hiddenDrawerSections = java.util.Collections.emptySet();
		model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(pageHeader, page, threadsHeader, group, thread, boardsHeader, board, settings), model.orderedDrawerItems);
	}

	@Test public void customVisibilityPreservesPositionAndCannotHideSettings() {
		DrawerContentController model = model();
		ListItem history = item(ListItem.Type.MENU, DrawerForm.MENU_ITEM_HISTORY, "history");
		ListItem replies = item(ListItem.Type.MENU, DrawerForm.MENU_ITEM_MY_POSTS, "replies");
		ListItem settings = item(ListItem.Type.MENU, DrawerForm.MENU_ITEM_PREFERENCES, "settings");
		model.menu.addAll(Arrays.asList(replies, history, settings));
		model.drawerSectionOrder = Arrays.asList(Preferences.DrawerSection.HISTORY,
				Preferences.DrawerSection.SETTINGS, Preferences.DrawerSection.REPLIES);
		// Also guard invalid/restored state, rather than relying only on the settings widget.
		model.hiddenDrawerSections = java.util.EnumSet.of(Preferences.DrawerSection.REPLIES, Preferences.DrawerSection.SETTINGS);
		model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(history, settings), model.orderedDrawerItems);
		assertEquals(Arrays.asList(replies, history, settings), model.menu);
		model.hiddenDrawerSections = java.util.Collections.emptySet();
		model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(history, settings, replies), model.orderedDrawerItems);
	}

	@Test public void visibilityDoesNotEnableUnavailableSectionsOrOverrideHidePages() {
		DrawerContentController model = model();
		ListItem page = item(ListItem.Type.PAGE, 0, "page");
		ListItem settings = item(ListItem.Type.MENU, DrawerForm.MENU_ITEM_PREFERENCES, "settings");
		model.pages.add(page); model.menu.add(settings);
		model.categoriesOrder = CategoriesOrder.HIDE_PAGES;
		model.drawerSectionOrder = Arrays.asList(Preferences.DrawerSection.PAGES,
				Preferences.DrawerSection.REDDIT_APP, Preferences.DrawerSection.SETTINGS);
		model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(settings), model.orderedDrawerItems);
		model.drawerSectionOrder = null;
		model.hiddenDrawerSections = java.util.EnumSet.of(Preferences.DrawerSection.REDDIT_APP);
		model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(settings), model.orderedDrawerItems);
		assertEquals(Arrays.asList(page), model.pages);
	}

	@Test public void buildingSectionsDoesNotAppendMenuActionsIntoTheLiveFeedList() {
		DrawerContentController model = model();
		ListItem header = item(ListItem.Type.SECTION, DrawerForm.SECTION_ACTION_COMBINED_FEEDS_SETTINGS, "feeds");
		ListItem feed = new ListItem(ListItem.Type.COMBINED_FEED, 0, "dvach", "feed-id", null, "feed");
		ListItem feedsMenu = item(ListItem.Type.MENU, DrawerForm.MENU_ITEM_COMBINED_FEEDS, "feeds menu");
		ListItem settings = item(ListItem.Type.MENU, DrawerForm.MENU_ITEM_PREFERENCES, "settings");
		model.pageNavigation.addAll(Arrays.asList(header, feed));
		model.menu.addAll(Arrays.asList(feedsMenu, settings));
		model.drawerSectionOrder = Arrays.asList(Preferences.DrawerSection.MY_BOARDS, Preferences.DrawerSection.SETTINGS);
		model.rebuildOrderedDrawerItems(); model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(header, feed, feedsMenu, settings), model.orderedDrawerItems);
		assertEquals(Arrays.asList(header, feed), model.pageNavigation);
		model.drawerSectionOrder = null;
		model.hiddenDrawerSections = java.util.EnumSet.of(Preferences.DrawerSection.MY_BOARDS);
		model.categoriesOrder = CategoriesOrder.PAGES_FIRST;
		model.rebuildOrderedDrawerItems();
		assertEquals(Arrays.asList(settings), model.orderedDrawerItems);
		assertEquals(Arrays.asList(header, feed), model.pageNavigation);
		assertEquals(Arrays.asList(feedsMenu, settings), model.menu);
	}
}
