package com.mishiranu.dashchan.content.storage;

import static org.junit.Assert.*;

import com.mishiranu.dashchan.content.storage.FavoritesStorage.FavoriteItem;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class FavoritesWatcherMigrationTest {
	@Test public void oldThreadsBecomeWatchedButBoardsAndOtherFieldsDoNotChange() {
		FavoriteItem thread = new FavoriteItem("forum", "b", "1");
		thread.title = "Custom title";
		thread.modifiedTitle = true;
		FavoriteItem watched = new FavoriteItem("forum", "b", "2");
		watched.watcherEnabled = true;
		FavoriteItem board = new FavoriteItem("forum", "b", null);
		List<FavoriteItem> items = Arrays.asList(thread, board, watched);
		assertTrue(FavoritesWatcherMigration.enableExistingThreads(items, 0));
		assertTrue(thread.watcherEnabled);
		assertTrue(watched.watcherEnabled);
		assertFalse(board.watcherEnabled);
		assertSame(thread, items.get(0)); assertSame(board, items.get(1)); assertSame(watched, items.get(2));
		assertEquals("Custom title", thread.title); assertTrue(thread.modifiedTitle);
	}

	@Test public void subsequentLoadsPreserveTheUsersDisabledWatcher() {
		FavoriteItem thread = new FavoriteItem("forum", "b", "1");
		List<FavoriteItem> items = Collections.singletonList(thread);
		assertTrue(FavoritesWatcherMigration.enableExistingThreads(items, 0));
		thread.watcherEnabled = false;
		assertFalse(FavoritesWatcherMigration.enableExistingThreads(items, FavoritesWatcherMigration.VERSION));
		assertFalse(thread.watcherEnabled);
	}

	@Test public void emptyOldSnapshotStillGetsItsOneTimeMarkerAndFutureVersionsAreNotReapplied() {
		assertTrue(FavoritesWatcherMigration.enableExistingThreads(Collections.emptyList(), 0));
		FavoriteItem thread = new FavoriteItem("forum", "b", "1");
		assertFalse(FavoritesWatcherMigration.enableExistingThreads(Collections.singletonList(thread),
				FavoritesWatcherMigration.VERSION + 1));
		assertFalse(thread.watcherEnabled);
	}
}
