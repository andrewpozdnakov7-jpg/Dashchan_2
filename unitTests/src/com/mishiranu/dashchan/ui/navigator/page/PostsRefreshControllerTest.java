package com.mishiranu.dashchan.ui.navigator.page;

import static org.junit.Assert.*;
import com.mishiranu.dashchan.content.model.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class PostsRefreshControllerTest {
	private static final class Host implements PostsRefreshController.Host {
		int interval = 1000, reads, progress, checkInterval;
		boolean read, extract, erasing, allowed = true, reload, visible;
		@Override public int autoRefreshInterval() { return interval; }
		@Override public boolean hasReadTask() { return read; }
		@Override public boolean hasExtractTask() { return extract; }
		@Override public boolean isErasing() { return erasing; }
		@Override public boolean canRefreshGallery() { return allowed; }
		@Override public void startProgress() { progress++; }
		@Override public void refreshRead(boolean reload, boolean visible, int checkInterval) {
			reads++; this.reload = reload; this.visible = visible; this.checkInterval = checkInterval;
		}
		@Override public Set<PostNumber> galleryPostNumbers() { return new HashSet<>(); }
		@Override public PostItem findPost(PostNumber number) { throw new AssertionError("No posts in fixture"); }
		@Override public List<GalleryItem> galleryItems() { return Collections.emptyList(); }
	}
	private static final class Scheduler implements PostsRefreshController.Scheduler {
		Runnable pending; int delay, removals;
		@Override public void remove(Runnable runnable) { removals++; if (pending == runnable) pending = null; }
		@Override public void post(Runnable runnable, int delay) { pending = runnable; this.delay = delay; }
		void fire() { Runnable runnable = pending; pending = null; runnable.run(); }
	}
	@Test public void automaticRefreshKeepsIntervalAndSuppressesReadWhileBusyOrErasing() {
		Host host = new Host(); Scheduler scheduler = new Scheduler();
		PostsRefreshController controller = new PostsRefreshController(host, scheduler);
		controller.queueNextRefresh(true); assertEquals(0, scheduler.delay);
		scheduler.fire(); assertEquals(1, host.reads); assertFalse(host.visible);
		assertEquals(1000, host.checkInterval); assertEquals(1000, scheduler.delay);
		host.read = true; scheduler.fire(); assertEquals(1, host.reads);
		host.read = false; host.erasing = true; scheduler.fire(); assertEquals(1, host.reads);
		host.interval = 0; scheduler.fire(); assertNull(scheduler.pending);
		controller.queueNextRefresh(false); controller.stopRefresh(); assertNull(scheduler.pending);
	}
	@Test public void manualReadPreservesProgressVisibilityAndReloadFlags() {
		Host host = new Host();
		PostsRefreshController controller = new PostsRefreshController(host, new Scheduler());
		controller.refreshPosts(true);
		assertEquals(1, host.progress); assertTrue(host.reload); assertTrue(host.visible);
		assertEquals(0, host.checkInterval);
		controller.refreshPostsWithoutIndication(false);
		assertEquals(1, host.progress); assertFalse(host.reload);
	}
	@Test public void galleryWaitsForReadAndExtractAndCompletesOnlyOnce() {
		Host host = new Host();
		PostsRefreshController controller = new PostsRefreshController(host, new Scheduler());
		int[] calls = {0};
		assertNotNull(controller.refreshGallery((items, count, error) -> {
			calls[0]++; assertNotNull(items); assertEquals(0, count); assertNull(error);
		}, Collections.emptySet()));
		assertNull(controller.refreshGallery((items, count, error) -> fail("Duplicate"), null));
		controller.finishGalleryRefresh(null); assertEquals(0, calls[0]);
		controller.markReadComplete(); host.extract = true;
		controller.finishGalleryRefresh(null); assertEquals(0, calls[0]);
		host.extract = false; host.read = true;
		controller.finishGalleryRefresh(null); assertEquals(0, calls[0]);
		host.read = false; controller.finishGalleryRefresh(null);
		controller.finishGalleryRefresh(null); assertEquals(1, calls[0]);
	}
	@Test public void cancelledGalleryRequestCannotCancelItsSuccessor() {
		Host host = new Host();
		PostsRefreshController controller = new PostsRefreshController(host, new Scheduler());
		Runnable old = controller.refreshGallery((items, count, error) -> fail("Cancelled"), null);
		old.run();
		controller.refreshGallery((items, count, error) -> {}, null);
		old.run(); assertTrue(controller.hasGalleryRefresh());
		host.allowed = false;
		assertNull(controller.refreshGallery((items, count, error) -> fail("Unavailable"), null));
	}
}
