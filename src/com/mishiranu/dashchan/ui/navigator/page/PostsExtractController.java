package com.mishiranu.dashchan.ui.navigator.page;

import com.mishiranu.dashchan.content.database.PostsDatabase;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.util.Collection;
import java.util.Set;

/** Extraction state changes shared by the page and behavioral tests; no scheduling or database access. */
final class PostsExtractController {
	private PostsExtractController() {}

	interface WindowHost {
		void preservePosition();
		void invalidateWindow();
		void reloadWindow();
		void cancelWindowTask();
		void clearWindowRequest();
		void completeWindowedLoading();
		void enterFullMode();
	}

	interface HiddenHost {
		void invalidateHidden();
		boolean refreshHiddenVisibility();
	}

	static boolean updateHiddenVisibility(boolean rulesChanged, boolean postsChanged, HiddenHost host) {
		if (rulesChanged) {
			host.invalidateHidden();
			return true;
		}
		return postsChanged && host.refreshHiddenVisibility();
	}

	/** False means a partial result needs another window, not a full adapter replacement. */
	static boolean transitionWindowToFull(int extractedCount, int itemCount, WindowHost host) {
		if (extractedCount < itemCount) {
			host.invalidateWindow();
			host.reloadWindow();
			return false;
		}
		host.preservePosition();
		host.invalidateWindow();
		host.cancelWindowTask();
		host.clearWindowRequest();
		host.completeWindowedLoading();
		host.enterFullMode();
		return true;
	}

	static boolean removeEmptyThread(boolean erase, boolean empty, Runnable removeFavorite, Runnable closePage) {
		if (!erase || !empty) return false;
		removeFavorite.run();
		closePage.run();
		return true;
	}

	static void markUnread(Set<PostNumber> unread, boolean newThread, Collection<PostNumber> allPosts,
			Collection<PostNumber> newPosts, Collection<PostNumber> deletedPosts, Collection<PostNumber> editedPosts) {
		if (newThread) {
			unread.addAll(allPosts);
		} else {
			unread.addAll(newPosts);
			unread.addAll(deletedPosts);
			unread.addAll(editedPosts);
		}
	}

	/** A null snapshot leaves flags alone. Hidden state still comes from the ordinary thread load. */
	static boolean replaceFlags(PostItem.HideState.Map<PostNumber> hidden, Set<PostNumber> user,
			PostsDatabase.Flags flags, long appliedUserRevision) {
		if (flags == null) return false;
		hidden.clear();
		hidden.addAll(flags.hiddenPosts);
		replaceUserFlags(user, flags, appliedUserRevision);
		return true;
	}

	static boolean replaceUserFlags(Set<PostNumber> user, PostsDatabase.Flags flags, long appliedRevision) {
		if (flags.revision < appliedRevision || user.equals(flags.userPosts)) return false;
		user.clear();
		user.addAll(flags.userPosts);
		return true;
	}
}
