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
}
