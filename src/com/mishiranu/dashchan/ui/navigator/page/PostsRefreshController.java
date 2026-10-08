package com.mishiranu.dashchan.ui.navigator.page;

import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.ui.gallery.GalleryRefreshCallback;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Page-scoped refresh orchestration; retained tasks/sessions remain in their existing ViewModels. */
final class PostsRefreshController {
	interface Host {
		int autoRefreshInterval();
		boolean hasReadTask();
		boolean hasExtractTask();
		boolean isErasing();
		boolean canRefreshGallery();
		void startProgress();
		void refreshRead(boolean reload, boolean visible, int checkInterval);
		Set<PostNumber> galleryPostNumbers();
		PostItem findPost(PostNumber number);
		List<GalleryItem> galleryItems();
	}
	interface Scheduler {
		void remove(Runnable runnable);
		void post(Runnable runnable, int delay);
	}
	private final Host host;
	private final Scheduler scheduler;
	private GalleryRefreshCallback galleryRefreshCallback;
	private Set<PostNumber> galleryRefreshKnownPosts;
	private boolean galleryRefreshReadComplete;
	private final Runnable refreshRunnable = this::runAutoRefresh;

	PostsRefreshController(Host host) {
		this(host, new Scheduler() {
			@Override public void remove(Runnable runnable) { ConcurrentUtils.HANDLER.removeCallbacks(runnable); }
			@Override public void post(Runnable runnable, int delay) {
				if (delay == 0) ConcurrentUtils.HANDLER.post(runnable);
				else ConcurrentUtils.HANDLER.postDelayed(runnable, delay);
			}
		});
	}
	PostsRefreshController(Host host, Scheduler scheduler) { this.host = host; this.scheduler = scheduler; }
	boolean hasReadTask() { return host.hasReadTask(); }
	boolean hasExtractTask() { return host.hasExtractTask(); }
	boolean hasGalleryRefresh() { return galleryRefreshCallback != null; }
	void markReadComplete() { galleryRefreshReadComplete = true; }
	void queueNextRefresh(boolean instant) {
		scheduler.remove(refreshRunnable);
		int interval = host.autoRefreshInterval();
		if (interval > 0) scheduler.post(refreshRunnable, instant ? 0 : interval);
	}
	void stopRefresh() { scheduler.remove(refreshRunnable); }
	private void runAutoRefresh() {
		int interval = host.autoRefreshInterval();
		if (interval > 0 && !hasReadTask() && !host.isErasing()) host.refreshRead(false, false, interval);
		queueNextRefresh(false);
	}
	void refreshPosts(boolean reload) { host.startProgress(); refreshPostsWithoutIndication(reload); }
	void refreshPostsWithoutIndication(boolean reload) { host.refreshRead(reload, true, 0); }
	Runnable refreshGallery(GalleryRefreshCallback callback, Set<PostNumber> knownPosts) {
		if (!host.canRefreshGallery() || galleryRefreshCallback != null) return null;
		galleryRefreshKnownPosts = knownPosts != null ? new HashSet<>(knownPosts) : host.galleryPostNumbers();
		galleryRefreshReadComplete = false;
		galleryRefreshCallback = callback;
		refreshPostsWithoutIndication(false);
		return () -> {
			if (galleryRefreshCallback == callback) {
				galleryRefreshCallback = null;
				galleryRefreshKnownPosts = null;
			}
		};
	}
	void finishGalleryRefresh(ErrorItem error) {
		if (galleryRefreshCallback == null) return;
		if (error == null && (!galleryRefreshReadComplete || hasReadTask() || hasExtractTask())) return;
		GalleryRefreshCallback callback = galleryRefreshCallback;
		int newPosts = 0;
		if (error == null) {
			for (PostNumber number : host.galleryPostNumbers()) {
				PostItem item = host.findPost(number);
				if (!item.isDeleted() && !galleryRefreshKnownPosts.contains(item.getPostNumber())) newPosts++;
			}
		}
		galleryRefreshCallback = null;
		galleryRefreshKnownPosts = null;
		callback.onComplete(error == null ? host.galleryItems() : null, newPosts, error);
	}
}
