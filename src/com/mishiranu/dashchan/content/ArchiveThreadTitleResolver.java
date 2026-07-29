package com.mishiranu.dashchan.content;

import chan.content.Chan;
import chan.content.model.ThreadSummary;
import chan.util.StringUtils;
import com.mishiranu.dashchan.content.database.CommonDatabase;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ArchiveThreadTitleResolver {
	private ArchiveThreadTitleResolver() {}

	public static List<ThreadSummary> resolve(Chan chan, List<ThreadSummary> threadSummaries) {
		if (threadSummaries.isEmpty()) {
			return threadSummaries;
		}
		Map<String, String> favoriteTitles = ConcurrentUtils.mainGet(() -> {
			HashMap<String, String> titles = new HashMap<>();
			FavoritesStorage favoritesStorage = FavoritesStorage.getInstance();
			for (ThreadSummary threadSummary : threadSummaries) {
				if (StringUtils.isEmptyOrWhitespace(threadSummary.getDescription())) {
					FavoritesStorage.FavoriteItem favoriteItem = favoritesStorage.getFavorite(chan.name,
							threadSummary.getBoardName(), threadSummary.getThreadNumber());
					if (favoriteItem != null && !StringUtils.isEmptyOrWhitespace(favoriteItem.title)) {
						titles.put(threadSummary.getThreadNumber(), favoriteItem.title);
					}
				}
			}
			return titles;
		});
		ArrayList<ThreadSummary> resolvedSummaries = null;
		for (int i = 0; i < threadSummaries.size(); i++) {
			ThreadSummary threadSummary = threadSummaries.get(i);
			String title = threadSummary.getDescription();
			if (StringUtils.isEmptyOrWhitespace(title)) {
				title = favoriteTitles.get(threadSummary.getThreadNumber());
			}
			if (StringUtils.isEmptyOrWhitespace(title)) {
				title = CommonDatabase.getInstance().getHistory().getTitle(chan.name,
						threadSummary.getBoardName(), threadSummary.getThreadNumber());
			}
			if (StringUtils.isEmptyOrWhitespace(title)) {
				Post originalPost = PagesDatabase.getInstance().getOriginalPost(new PagesDatabase.ThreadKey(chan.name,
						threadSummary.getBoardName(), threadSummary.getThreadNumber()));
				if (originalPost != null) {
					title = PostItem.createPost(originalPost, chan, threadSummary.getBoardName(),
							threadSummary.getThreadNumber(), originalPost.number).getSubjectOrComment();
				}
			}
			if (!StringUtils.isEmptyOrWhitespace(title) &&
					!title.equals(threadSummary.getDescription())) {
				if (resolvedSummaries == null) {
					resolvedSummaries = new ArrayList<>(threadSummaries);
				}
				resolvedSummaries.set(i, new ThreadSummary(threadSummary.getBoardName(),
						threadSummary.getThreadNumber(), title).setPostsCount(threadSummary.getPostsCount()));
			}
		}
		return resolvedSummaries != null ? resolvedSummaries : threadSummaries;
	}
}
