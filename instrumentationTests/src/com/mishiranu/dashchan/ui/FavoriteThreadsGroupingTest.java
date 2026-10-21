package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.storage.FavoritesStorage.FavoriteItem;
import com.mishiranu.dashchan.ui.DrawerContentController.ListItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Disposable model snapshots; does not write real favorites, read state or preferences. */
@RunWith(AndroidJUnit4.class)
public class FavoriteThreadsGroupingTest {
	private final HashSet<String> unread = new HashSet<>(Collections.singletonList("u"));
	private DrawerContentController model() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		DrawerContentController model = new DrawerContentController(context, () -> "dvach", null,
				item -> "deleted".equals(item.threadNumber), item -> unread.contains(item.threadNumber));
		model.favoritesGroupedByBoard = true; model.favoritesUnreadFirst = true;
		return model;
	}
	private List<FavoriteItem> source() {
		return Arrays.asList(new FavoriteItem("dvach", "z", "z1"), new FavoriteItem("dvach", "b", "r"),
				new FavoriteItem("dvach", "b", "u"), new FavoriteItem("dvach", "b", "deleted"));
	}
	private List<String> threads(List<ListItem> items) {
		List<String> result = new ArrayList<>();
		for (ListItem item : items) if (item.type == ListItem.Type.FAVORITE && item.isThreadItem()) result.add(item.threadNumber);
		return result;
	}
	private List<ListItem> groups(DrawerContentController model) {
		List<ListItem> result = new ArrayList<>();
		for (ListItem item : model.displayedFavorites) if (item.type == ListItem.Type.FAVORITE_GROUP) result.add(item);
		return result;
	}
	@Test public void groupedUnreadDisplayDoesNotRewriteTheStorageOrderOrResurrectDeletedRows() {
		DrawerContentController model = model();
		model.updateListFavorites(Collections.emptyList(), source(), true, false);
		assertEquals(Arrays.asList("u", "r", "z1"), threads(model.displayedFavorites));
		assertEquals(Arrays.asList("z1", "r", "u"), threads(model.favorites));
		assertEquals(2, groups(model).size()); assertEquals("b", groups(model).get(0).boardName);
		model.favoritesGroupedByBoard = false; model.favoritesUnreadFirst = false;
		model.updateDisplayedFavorites(false);
		assertEquals(Arrays.asList("z1", "r", "u"), threads(model.displayedFavorites));
		assertTrue(groups(model).isEmpty());
	}
	@Test public void perBoardAndGlobalCollapsePreserveCountsAndEmptyGroupDisappears() {
		DrawerContentController model = model();
		model.updateListFavorites(Collections.emptyList(), source(), true, false);
		model.collapsedFavoriteBoardGroups.add(Preferences.favoriteBoardGroupKey("dvach", "b"));
		model.updateDisplayedFavorites(false);
		assertEquals(Collections.singletonList("z1"), threads(model.displayedFavorites));
		assertFalse(groups(model).get(0).expanded); assertEquals(3, model.getVisibleFavoriteThreadCount());
		model.updateDisplayedFavorites(true);
		assertTrue(threads(model.displayedFavorites).isEmpty()); assertTrue(groups(model).isEmpty());
		model.updateDisplayedFavorites(false); assertEquals(2, groups(model).size());
		assertTrue(model.removeFavoriteThreadFromList("dvach", "b", "r", false));
		assertTrue(model.removeFavoriteThreadFromList("dvach", "b", "u", false));
		assertEquals(1, groups(model).size()); assertEquals("z", groups(model).get(0).boardName);
	}
	@Test public void customDrawerOrderingKeepsNestedGroupHeadersInTheFavoriteThreadsBlock() {
		DrawerContentController model = model();
		model.updateListFavorites(Collections.emptyList(), source(), true, false);
		ListItem settings = new ListItem(ListItem.Type.MENU, DrawerForm.MENU_ITEM_PREFERENCES, 0, "settings");
		model.menu.add(settings);
		model.drawerSectionOrder = Arrays.asList(Preferences.DrawerSection.FAVORITE_THREADS, Preferences.DrawerSection.SETTINGS);
		model.rebuildOrderedDrawerItems();
		assertEquals(groups(model).get(0), model.orderedDrawerItems.get(1));
		assertEquals(settings, model.orderedDrawerItems.get(model.orderedDrawerItems.size() - 1));
	}
	@Test public void groupIdentityIsIndependentOfTextAndUnreadStateIsFrozenDuringDrag() {
		assertEquals(ListItem.favoriteGroup("dvach", "b", "old", true).id,
				ListItem.favoriteGroup("dvach", "b", "new", false).id);
		assertNotEquals(ListItem.favoriteGroup("dvach", "b", "x", true).id,
				ListItem.favoriteGroup("fourchan", "b", "x", true).id);
		DrawerContentController model = model();
		model.updateListFavorites(Collections.emptyList(), source(), true, false);
		model.freezeFavoriteUnreadOrder = true; unread.clear(); unread.add("r");
		model.updateDisplayedFavorites(false);
		assertEquals(Arrays.asList("u", "r", "z1"), threads(model.displayedFavorites));
		model.freezeFavoriteUnreadOrder = false; model.updateDisplayedFavorites(false);
		assertEquals(Arrays.asList("r", "u", "z1"), threads(model.displayedFavorites));
	}
}
