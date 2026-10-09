package com.mishiranu.dashchan.ui.navigator;

import chan.util.CommonUtils;
import com.mishiranu.dashchan.content.storage.CombinedFeedStorage;

/** Explicit list origin, independent of the most recently selected forum or feed. */
public final class ThreadListNavigation {
	private ThreadListNavigation() {}

	public static boolean matches(Page origin, Page thread, CombinedFeedStorage.Feed feed) {
		if (origin == null || thread == null || thread.content != Page.Content.POSTS) return false;
		if (origin.content == Page.Content.THREADS) {
			return CommonUtils.equals(origin.chanName, thread.chanName)
					&& CommonUtils.equals(origin.boardName, thread.boardName);
		}
		if (origin.content == Page.Content.COMBINED_THREADS && feed != null && feed.sources.size() >= 2
				&& CommonUtils.equals(origin.boardName, feed.id)) {
			for (CombinedFeedStorage.Source source : feed.sources) {
				if (CommonUtils.equals(source.chanName, thread.chanName)
						&& CommonUtils.equals(source.boardName, thread.boardName)) return true;
			}
		}
		return false;
	}

	public static Page fallback(Page thread) {
		return new Page(Page.Content.THREADS, thread.chanName, thread.boardName, null, null);
	}
}
