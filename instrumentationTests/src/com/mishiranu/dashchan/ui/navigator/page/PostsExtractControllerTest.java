package com.mishiranu.dashchan.ui.navigator.page;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.content.database.PostsDatabase;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs the state-changing operations used by PostsPage, without opening real threads/databases. */
@RunWith(AndroidJUnit4.class)
public class PostsExtractControllerTest {
	private static PostNumber post(int number) { return new PostNumber(number, 0); }
	private static HashSet<PostNumber> set(int... numbers) {
		HashSet<PostNumber> result = new HashSet<>();
		for (int number : numbers) result.add(post(number));
		return result;
	}

	@Test public void changedPostsBecomeUnreadWithoutLosingExistingUnreadOrMarkingUnchanged() {
		HashSet<PostNumber> unread = set(1);
		PostsExtractController.markUnread(unread, false, set(2, 3, 4, 5), set(2), set(3), set(4));
		assertEquals(set(1, 2, 3, 4), unread);
		PostsExtractController.markUnread(unread, false, set(2, 3, 4, 5), set(2), set(3), set(4));
		assertEquals(set(1, 2, 3, 4), unread);
		PostsExtractController.markUnread(unread, false, set(5), set(), set(), set());
		assertEquals(set(1, 2, 3, 4), unread);
	}

	@Test public void firstExtractionMarksAllLoadedPostsNotUnrelatedDeltaEntries() {
		HashSet<PostNumber> unread = set(1);
		PostsExtractController.markUnread(unread, true, set(2, 3), set(99), set(98), set(97));
		assertEquals(set(1, 2, 3), unread);
	}

	@Test public void flagsReplaceHiddenShownAndUserStateAndEmptySnapshotClearsIt() {
		PostItem.HideState.Map<PostNumber> hidden = new PostItem.HideState.Map<>();
		hidden.set(post(1), PostItem.HideState.HIDDEN);
		HashSet<PostNumber> user = set(1);
		PostItem.HideState.Map<PostNumber> suppliedHidden = new PostItem.HideState.Map<>();
		suppliedHidden.set(post(2), PostItem.HideState.HIDDEN);
		suppliedHidden.set(post(3), PostItem.HideState.SHOWN);
		PostsDatabase.Flags flags = new PostsDatabase.Flags(suppliedHidden, set(3));
		assertTrue(PostsExtractController.replaceFlags(hidden, user, flags));
		assertEquals(2, hidden.size());
		assertEquals(PostItem.HideState.UNDEFINED, hidden.get(post(1)));
		assertEquals(PostItem.HideState.HIDDEN, hidden.get(post(2)));
		assertEquals(PostItem.HideState.SHOWN, hidden.get(post(3)));
		assertEquals(set(3), user);
		assertFalse(PostsExtractController.replaceFlags(hidden, user, null));
		assertEquals(PostItem.HideState.HIDDEN, hidden.get(post(2)));
		// Target state must not alias the extraction snapshot.
		hidden.set(post(2), PostItem.HideState.UNDEFINED); user.clear();
		assertEquals(PostItem.HideState.HIDDEN, suppliedHidden.get(post(2)));
		assertEquals(set(3), flags.userPosts);
		assertTrue(PostsExtractController.replaceFlags(hidden, user,
				new PostsDatabase.Flags(new PostItem.HideState.Map<>(), set())));
		assertEquals(0, hidden.size()); assertTrue(user.isEmpty());
	}

	private static final class HiddenHost implements PostsExtractController.HiddenHost {
		int invalidations, refreshes;
		boolean refreshed;
		@Override public void invalidateHidden() { invalidations++; }
		@Override public boolean refreshHiddenVisibility() { refreshes++; return refreshed; }
	}

	@Test public void flagsOrThreadRulesInvalidateHiddenAndChangedPostsRefreshWithoutReplacingRules() {
		HiddenHost host = new HiddenHost();
		assertTrue(PostsExtractController.updateHiddenVisibility(true, true, host));
		assertEquals(1, host.invalidations); assertEquals(0, host.refreshes);
		assertTrue(PostsExtractController.updateHiddenVisibility(true, false, host));
		assertEquals(2, host.invalidations); assertEquals(0, host.refreshes);
		assertFalse(PostsExtractController.updateHiddenVisibility(false, false, host));
		assertEquals(2, host.invalidations); assertEquals(0, host.refreshes);
		assertFalse(PostsExtractController.updateHiddenVisibility(false, true, host));
		assertEquals(1, host.refreshes);
		host.refreshed = true;
		assertTrue(PostsExtractController.updateHiddenVisibility(false, true, host));
		assertEquals(2, host.refreshes); assertEquals(2, host.invalidations);
	}

	private static final class WindowHost implements PostsExtractController.WindowHost {
		final List<String> calls = new ArrayList<>();
		boolean windowed = true;
		@Override public void preservePosition() { calls.add("position"); }
		@Override public void invalidateWindow() { calls.add("invalidate"); }
		@Override public void reloadWindow() { calls.add("reload"); }
		@Override public void cancelWindowTask() { calls.add("cancel"); }
		@Override public void clearWindowRequest() { calls.add("clear"); }
		@Override public void completeWindowedLoading() { calls.add("complete"); }
		@Override public void enterFullMode() { calls.add("full"); windowed = false; }
	}

	@Test public void fullResultPreservesPositionBeforeClearingWindowAndEnteringFullMode() {
		for (int count : new int[]{20, 21}) {
			WindowHost host = new WindowHost();
			assertTrue(PostsExtractController.transitionWindowToFull(count, 20, host));
			assertEquals(Arrays.asList("position", "invalidate", "cancel", "clear", "complete", "full"), host.calls);
			assertFalse(host.windowed);
		}
	}

	@Test public void partialResultReloadsWindowWithoutCancellingOrSwitchingToFull() {
		WindowHost host = new WindowHost();
		assertFalse(PostsExtractController.transitionWindowToFull(19, 20, host));
		assertEquals(Arrays.asList("invalidate", "reload"), host.calls);
		assertTrue(host.windowed);
	}

	@Test public void eraseOnlyRemovesAnEmptyThreadAndRemovesFavoriteBeforeClosing() {
		List<String> calls = new ArrayList<>();
		assertFalse(PostsExtractController.removeEmptyThread(false, true, () -> calls.add("remove"), () -> calls.add("close")));
		assertFalse(PostsExtractController.removeEmptyThread(true, false, () -> calls.add("remove"), () -> calls.add("close")));
		assertTrue(calls.isEmpty());
		assertTrue(PostsExtractController.removeEmptyThread(true, true, () -> calls.add("remove"), () -> calls.add("close")));
		assertEquals(Arrays.asList("remove", "close"), calls);
	}
}
