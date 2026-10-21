package com.mishiranu.dashchan.content.storage;

import java.util.List;

/** Applied only when loading favorites written before the default-on policy. */
final class FavoritesWatcherMigration {
	static final int VERSION = 1;

	private FavoritesWatcherMigration() {}

	static boolean enableExistingThreads(List<FavoritesStorage.FavoriteItem> items, int savedVersion) {
		if (savedVersion >= VERSION) return false;
		for (FavoritesStorage.FavoriteItem item : items) {
			if (item.threadNumber != null) item.watcherEnabled = true;
		}
		return true;
	}
}
