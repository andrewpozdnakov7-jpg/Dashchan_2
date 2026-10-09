package com.mishiranu.dashchan.ui.navigator;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.os.Parcel;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.storage.CombinedFeedStorage;
import com.mishiranu.dashchan.ui.StackItem;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ThreadListNavigationTest {
	private Page thread(String forum, String board) {
		return new Page(Page.Content.POSTS, forum, board, "123", null);
	}

	private Page board(String forum, String board) {
		return new Page(Page.Content.THREADS, forum, board, null, null);
	}

	private CombinedFeedStorage.Feed feed(String id) {
		CombinedFeedStorage.Feed feed = new CombinedFeedStorage.Feed();
		feed.id = id;
		feed.sources.add(new CombinedFeedStorage.Source("forum", "mobi"));
		feed.sources.add(new CombinedFeedStorage.Source("other", "b"));
		return feed;
	}

	@Test public void defaultAndPersistedValuesAreStable() {
		assertSame(Preferences.ThreadToolbarAction.BACK, Preferences.DEFAULT_THREAD_TOOLBAR_ACTION);
		assertEquals("back", Preferences.ThreadToolbarAction.BACK.value);
		assertEquals("thread_list", Preferences.ThreadToolbarAction.THREAD_LIST.value);
		assertEquals("hidden", Preferences.ThreadToolbarAction.HIDDEN.value);
	}

	@Test public void regularOriginRequiresBothForumAndBoard() {
		Page thread = thread("forum", "mobi");
		assertTrue(ThreadListNavigation.matches(board("forum", "mobi"), thread, null));
		assertFalse(ThreadListNavigation.matches(board("forum", "b"), thread, null));
		assertFalse(ThreadListNavigation.matches(board("other", "mobi"), thread, null));
		assertEquals(board("forum", "mobi"), ThreadListNavigation.fallback(thread));
	}

	@Test public void combinedOriginUsesExactFeedAndSourceNotJustForum() {
		Page origin = new Page(Page.Content.COMBINED_THREADS, "forum", "feed-id", null, null);
		CombinedFeedStorage.Feed feed = feed("feed-id");
		assertTrue(ThreadListNavigation.matches(origin, thread("forum", "mobi"), feed));
		assertTrue(ThreadListNavigation.matches(origin, thread("other", "b"), feed));
		assertFalse(ThreadListNavigation.matches(origin, thread("forum", "b"), feed));
		assertFalse(ThreadListNavigation.matches(origin, thread("forum", "mobi"), feed("different-id")));
	}

	@Test public void removedOrInvalidFeedCannotBecomeOrigin() {
		Page origin = new Page(Page.Content.COMBINED_THREADS, "forum", "feed-id", null, null);
		Page thread = thread("forum", "mobi");
		assertFalse(ThreadListNavigation.matches(origin, thread, null));
		CombinedFeedStorage.Feed feed = feed("feed-id");
		feed.sources.remove(1);
		assertFalse(ThreadListNavigation.matches(origin, thread, feed));
		assertFalse(ThreadListNavigation.matches(null, thread, null));
		assertFalse(ThreadListNavigation.matches(board("forum", "mobi"), board("forum", "mobi"), null));
	}

	@Test public void originUpdateDoesNotChangeAnotherSavedSnapshot() {
		PageFragment fragment = new PageFragment(thread("forum", "mobi"), "retained");
		Bundle original = fragment.requireArguments();
		assertNull(fragment.getThreadListOrigin());
		Page origin = board("forum", "mobi");
		fragment.setThreadListOrigin(origin);
		assertNotSame(original, fragment.requireArguments());
		PageFragment old = new PageFragment();
		old.setArguments(original);
		assertNull(old.getThreadListOrigin());
		assertEquals(origin, fragment.getThreadListOrigin());
		assertEquals("retained", fragment.getRetainId());
	}

	@Test public void originSurvivesExistingStackParcelWithoutChangingPageFormat() {
		PageFragment fragment = new PageFragment(thread("forum", "mobi"), "retained");
		Page origin = new Page(Page.Content.COMBINED_THREADS, "forum", "feed-id", null, null);
		fragment.setThreadListOrigin(origin);
		StackItem stack = new StackItem(PageFragment.class.getName(), fragment.requireArguments(), null);
		Parcel parcel = Parcel.obtain();
		try {
			stack.writeToParcel(parcel, 0);
			parcel.setDataPosition(0);
			PageFragment restored = (PageFragment) StackItem.CREATOR.createFromParcel(parcel).create(null);
			assertEquals(fragment.getPage(), restored.getPage());
			assertEquals(origin, restored.getThreadListOrigin());
			assertEquals("retained", restored.getRetainId());
		} finally {
			parcel.recycle();
		}
	}
}
