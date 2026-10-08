package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.storage.FavoritesStorage.FavoriteItem;
import com.mishiranu.dashchan.content.storage.RedditPageStorage;
import com.mishiranu.dashchan.ui.DrawerContentController.ListItem;
import com.mishiranu.dashchan.ui.DrawerForm.Page;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the production model with isolated snapshots; never edits user storage/preferences. */
@RunWith(AndroidJUnit4.class)
public class DrawerContentBehaviorTest {
	private DrawerContentController model(String forum, Predicate<FavoriteItem> hidden) {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		return new DrawerContentController(context, () -> forum, null, hidden);
	}

	private static List<ListItem> rows(List<ListItem> items, ListItem.Type type) {
		List<ListItem> result = new ArrayList<>();
		for (ListItem item : items) if (item.type == type) result.add(item);
		return result;
	}

	private static List<String> threadNumbers(List<ListItem> items) {
		List<String> result = new ArrayList<>();
		for (ListItem item : items) if (item.isThreadItem()) result.add(item.threadNumber);
		return result;
	}

	private static void applyPages(DrawerContentController model, List<Page> pages) {
		model.updateListPages(() -> pages,
				() -> { throw new AssertionError("Ordinary forums must not read Reddit storage"); },
				() -> { throw new AssertionError("Ordinary forums must not read the Reddit URL"); });
	}

	@Test public void limitTwelveRetainsOldCurrentThreadAndExpandsWithoutDuplicates() {
		DrawerContentController model = model("dvach", item -> false);
		model.collapseLongOpenThreadsEnabled = true;
		List<Page> pages = new ArrayList<>();
		// Current thread is older than all twelve initially visible candidates.
		for (int i = 0; i < 14; i++) pages.add(new Page("dvach", "test", "t" + i, "title", i, i == 0));
		pages.add(new Page("dvach", "test", null, null, 20, false));
		applyPages(model, pages);
		assertEquals(Arrays.asList("t13", "t12", "t11", "t10", "t9", "t8", "t7", "t6", "t5", "t4", "t3", "t0"),
				threadNumbers(rows(model.pages, ListItem.Type.PAGE)));
		assertEquals(13, rows(model.pages, ListItem.Type.PAGE).size()); // Board is not part of the thread limit.
		assertEquals(2, model.collapsedPages.size());
		assertEquals("t2", model.collapsedPages.get(0).threadNumber);
		assertEquals("t1", model.collapsedPages.get(1).threadNumber);
		ListItem toggle = rows(model.pages, ListItem.Type.PAGES_TOGGLE).get(0);
		assertEquals(0, toggle.data);
		assertEquals(InstrumentationRegistry.getInstrumentation().getTargetContext()
				.getString(R.string.show_remaining_open_threads__format, 2), toggle.title);
		List<ListItem> snapshot = new ArrayList<>(model.pages);
		model.pagesExpanded = true;
		applyPages(model, pages);
		assertEquals(14, threadNumbers(rows(model.pages, ListItem.Type.PAGE)).size());
		assertTrue(model.collapsedPages.isEmpty());
		assertEquals(1, rows(model.pages, ListItem.Type.PAGES_TOGGLE).get(0).data);
		assertEquals(12, threadNumbers(rows(snapshot, ListItem.Type.PAGE)).size());
		assertEquals(1, rows(model.pages, ListItem.Type.SECTION).size());
	}

	@Test public void boundaryAndForumFilterResetExpansionAndNeverHideOtherForums() {
		DrawerContentController model = model("dvach", item -> false);
		model.collapseLongOpenThreadsEnabled = true;
		model.pagesExpanded = true;
		List<Page> pages = new ArrayList<>();
		for (int i = 0; i < 12; i++) pages.add(new Page("dvach", "test", "t" + i, "title", i, false));
		pages.add(new Page("fourchan", "g", "other", "title", 100, true));
		applyPages(model, pages);
		assertEquals(12, threadNumbers(rows(model.pages, ListItem.Type.PAGE)).size());
		assertFalse(threadNumbers(model.pages).contains("other"));
		assertTrue(rows(model.pages, ListItem.Type.PAGES_TOGGLE).isEmpty());
		assertTrue(model.collapsedPages.isEmpty());
		assertFalse(model.pagesExpanded);
		model.mergeChans = true;
		applyPages(model, pages);
		assertEquals(12, threadNumbers(rows(model.pages, ListItem.Type.PAGE)).size());
		assertTrue(threadNumbers(model.pages).contains("other"));
		assertEquals("t0", model.collapsedPages.get(0).threadNumber);
		model.collapseLongOpenThreadsEnabled = false;
		applyPages(model, pages);
		assertEquals(13, threadNumbers(rows(model.pages, ListItem.Type.PAGE)).size());
		assertTrue(rows(model.pages, ListItem.Type.PAGES_TOGGLE).isEmpty());
		assertTrue(model.collapsedPages.isEmpty());
		applyPages(model, Collections.emptyList());
		assertTrue(model.pages.isEmpty());
		assertFalse(model.pagesExpanded);
	}

	@Test public void redditHasSameLimitAndRetainsCurrentThreadAndSubredditRow() {
		DrawerContentController model = model(DrawerForm.CHAN_REDDIT, item -> false);
		model.collapseLongOpenThreadsEnabled = true;
		List<RedditPageStorage.Entry> pages = new ArrayList<>();
		for (int i = 14; i >= 1; i--) {
			RedditPageStorage.Entry entry = RedditPageStorage.parse(
					"https://www.reddit.com/r/test/comments/id" + i + "/title/", "thread" + i);
			assertNotNull(entry); pages.add(entry);
		}
		RedditPageStorage.Entry board = RedditPageStorage.parse("https://www.reddit.com/r/test/", "test");
		assertNotNull(board); pages.add(board);
		String currentUrl = pages.get(13).url;
		model.updateListPages(() -> { throw new AssertionError("Reddit must not read ordinary pages"); },
				() -> pages, () -> currentUrl);
		List<ListItem> visible = rows(model.pages, ListItem.Type.REDDIT_PAGE);
		assertEquals(13, visible.size());
		assertEquals(12, threadNumbers(visible).size());
		assertTrue(visible.stream().anyMatch(item -> currentUrl.equals(item.boardName)));
		assertTrue(visible.stream().anyMatch(item -> board.url.equals(item.boardName)));
		assertEquals(Arrays.asList("id3", "id2"), Arrays.asList(
				model.collapsedRedditPages.get(0).threadId, model.collapsedRedditPages.get(1).threadId));
		model.pagesExpanded = true;
		model.updateListPages(Collections::emptyList, () -> pages, () -> currentUrl);
		assertEquals(15, rows(model.pages, ListItem.Type.REDDIT_PAGE).size());
		assertTrue(model.collapsedRedditPages.isEmpty());
	}

	@Test public void favoritesFilterForumMissingForumAndDeletedThenRebuildOnRemoval() {
		DrawerContentController model = model("dvach", item -> "deleted".equals(item.threadNumber));
		List<FavoriteItem> source = Arrays.asList(new FavoriteItem("dvach", "test", "live"),
				new FavoriteItem("dvach", "test", "deleted"), new FavoriteItem("fourchan", "g", "other"),
				new FavoriteItem("missing-forum-test", "test", "missing"));
		model.updateListFavorites(Collections.emptyList(), source, true, false);
		assertEquals(Collections.singletonList("live"), threadNumbers(model.displayedFavorites));
		assertEquals(1, model.getVisibleFavoriteThreadCount());
		model.mergeChans = true;
		model.updateListFavorites(Collections.emptyList(), source, true, false);
		assertEquals(Arrays.asList("live", "other"), threadNumbers(model.displayedFavorites));
		model.updateListFavorites(Collections.emptyList(), source, false, false);
		assertEquals(Arrays.asList("live", "deleted", "other"), threadNumbers(model.displayedFavorites));
		model.updateListFavorites(Collections.emptyList(), source, true, true);
		assertTrue(threadNumbers(model.displayedFavorites).isEmpty());
		assertFalse(model.displayedFavorites.get(0).expanded);
		// Board rows remain visible when only the thread section is collapsed.
		model.favorites.add(new ListItem(ListItem.Type.FAVORITE, "dvach", "test", null, "board"));
		model.updateDisplayedFavorites(true);
		assertEquals(1, rows(model.displayedFavorites, ListItem.Type.FAVORITE).size());
		assertNull(rows(model.displayedFavorites, ListItem.Type.FAVORITE).get(0).threadNumber);
		assertFalse(model.removeFavoriteThreadFromList("dvach", "test", "other", false));
		assertTrue(model.removeFavoriteThreadFromList("fourchan", "g", "other", false));
		assertEquals(Collections.singletonList("live"), threadNumbers(model.displayedFavorites));
		assertEquals(1, model.getVisibleFavoriteThreadCount());
		assertEquals(4, source.size()); // Snapshot not mutated by model operations.
	}

	@Test public void noSelectedForumShowsAllFavoriteThreadsButDoesNotResurrectHiddenOnes() {
		DrawerContentController model = model(null, item -> "deleted".equals(item.threadNumber));
		model.updateListFavorites(Collections.emptyList(), Arrays.asList(
				new FavoriteItem("dvach", "test", "live"), new FavoriteItem("dvach", "test", "deleted"),
				new FavoriteItem("fourchan", "g", "other")), true, false);
		assertEquals(Arrays.asList("live", "other"), threadNumbers(model.displayedFavorites));
	}
}
